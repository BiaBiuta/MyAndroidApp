'use strict';

const fs = require('node:fs');
const path = require('node:path');
const iot = require('./iot');
const { ApiError } = require('./http');

/**
 * Trackerele WanderLog (IoT): fiecare tracker are cheia lui (per dispozitiv), inregistrata de
 * proprietar din aplicatie dupa asocierea cu cod. Trackerul trimite locurile vizitate semnand
 * fiecare cerere; serverul verifica semnatura, ora si nonce-ul (anti-reluare).
 * Un tracker pierdut se revoca din aplicatie: de atunci orice cerere a lui e respinsa (403).
 */
function registerDeviceRoutes(router, store, settings) {
  const replay = new iot.ReplayGuard();
  const updatesDir = settings.updatesDir || path.join(__dirname, '..', 'updates');
  const MAX_VISITS_PER_DEVICE = 500;

  store.data.devices = store.data.devices || [];
  store.data.deviceVisits = store.data.deviceVisits || [];

  const findDevice = (id) => store.data.devices.find((d) => d.id === id) || null;

  function publicDevice(device) {
    const visits = store.data.deviceVisits.filter((v) => v.device_id === device.id);
    return {
      id: device.id,
      name: device.name,
      model: device.model,
      firmware: device.firmware,
      status: device.status,
      created_at: device.created_at,
      revoked_at: device.revoked_at || null,
      last_seen_at: device.last_seen_at || null,
      visit_count: visits.length,
    };
  }

  /** Doar conturile USER cu cont propriu au trackere (nu adminii, nu vizitatorii anonimi). */
  function requireDeviceOwner(user) {
    if (user.role !== 'USER') throw new ApiError(403, 'Doar utilizatorii obișnuiți pot asocia trackere.');
    if (user.provider === 'ANONYMOUS') throw new ApiError(403, 'Creați un cont pentru a asocia un tracker.');
  }

  function ownedDeviceOr404(id, user) {
    const device = findDevice(id);
    // 404 si pentru trackerul altcuiva: nu dezvaluim ca exista
    if (!device || device.owner_id !== user.id) throw new ApiError(404, 'Trackerul nu există.');
    return device;
  }

  // ---------- Pentru proprietar (token JWT) ----------

  router.add(
    'POST',
    '/devices',
    ({ body, user }) => {
      requireDeviceOwner(user);
      let publicKey;
      try {
        publicKey = iot.importPublicKey(body.publicKey);
      } catch {
        throw new ApiError(400, 'Cheia publică a trackerului nu este validă (EC P-256).');
      }
      const der = iot.publicKeyDer(publicKey);
      // id-ul vine din cheie: nimeni nu poate inregistra o cheie straina sub id-ul altui tracker
      if (body.id !== iot.deviceIdFor(der)) throw new ApiError(400, 'Identificatorul nu corespunde cheii trackerului.');
      const name = typeof body.name === 'string' && body.name.trim() ? body.name.trim().slice(0, 60) : 'WanderLog Tracker';
      const existing = findDevice(body.id);
      if (existing) {
        if (existing.status === 'REVOKED') {
          throw new ApiError(409, 'Acest tracker a fost revocat. Resetează-l (generează o cheie nouă) și asociază-l din nou.');
        }
        if (existing.owner_id !== user.id) throw new ApiError(409, 'Trackerul este asociat cu alt cont.');
        existing.name = name;
        store.save();
        return { status: 200, body: publicDevice(existing) };
      }
      const device = {
        id: body.id,
        owner_id: user.id,
        name,
        model: typeof body.model === 'string' ? body.model.slice(0, 20) : 'WLT-1',
        firmware: typeof body.firmware === 'string' ? body.firmware.slice(0, 20) : '1.0.0',
        public_key: iot.b64(der),
        status: 'ACTIVE',
        created_at: new Date().toISOString(),
      };
      store.data.devices.push(device);
      store.save();
      return { status: 201, body: publicDevice(device) };
    },
    { auth: true },
  );

  router.add(
    'GET',
    '/devices',
    ({ user }) => ({
      status: 200,
      body: store.data.devices.filter((d) => d.owner_id === user.id).map(publicDevice),
    }),
    { auth: true },
  );

  // Tracker pierdut / furat: cheia lui nu mai e acceptata. Revocarea e definitiva.
  router.add(
    'POST',
    '/devices/:id/revoke',
    ({ params, user }) => {
      const device = ownedDeviceOr404(params.id, user);
      if (device.status !== 'REVOKED') {
        device.status = 'REVOKED';
        device.revoked_at = new Date().toISOString();
        store.save();
      }
      return { status: 200, body: publicDevice(device) };
    },
    { auth: true },
  );

  // Versiunea de firmware dupa o actualizare instalata din aplicatie
  router.add(
    'POST',
    '/devices/:id/firmware',
    ({ params, body, user }) => {
      const device = ownedDeviceOr404(params.id, user);
      if (typeof body.version !== 'string' || !/^\d+(\.\d+){0,3}$/.test(body.version)) {
        throw new ApiError(400, 'Versiune invalidă.');
      }
      device.firmware = body.version;
      store.save();
      return { status: 200, body: publicDevice(device) };
    },
    { auth: true },
  );

  router.add(
    'GET',
    '/devices/:id/visits',
    ({ params, user }) => {
      const device = ownedDeviceOr404(params.id, user);
      const visits = store.data.deviceVisits
        .filter((v) => v.device_id === device.id)
        .sort((a, b) => b.at.localeCompare(a.at));
      return { status: 200, body: visits };
    },
    { auth: true },
  );

  /**
   * Actualizarile publicate de producator (fisiere din server/updates). Serverul doar le
   * distribuie: NU le poate semna, deci aplicatia le verifica singura cu cheia producatorului.
   */
  router.add(
    'GET',
    '/device-updates',
    () => {
      let files = [];
      try {
        files = fs.readdirSync(updatesDir).filter((f) => f.endsWith('.json')).sort();
      } catch {
        files = [];
      }
      const manifests = [];
      for (const file of files) {
        try {
          const manifest = JSON.parse(fs.readFileSync(path.join(updatesDir, file), 'utf8'));
          manifests.push({ id: file.replace(/\.json$/, ''), ...manifest });
        } catch {
          // fisier stricat: il sarim
        }
      }
      return { status: 200, body: manifests };
    },
    { auth: true },
  );

  // ---------- Pentru tracker (semnatura cu cheia lui, fara token) ----------

  /** Verifica o cerere semnata de tracker; intoarce trackerul sau arunca 401/403. */
  function authenticateDevice(req, method, routePath) {
    const id = req.headers['x-wl-device'];
    const headers = {
      time: req.headers['x-wl-time'],
      nonce: req.headers['x-wl-nonce'],
      signature: req.headers['x-wl-signature'],
    };
    const device = typeof id === 'string' ? findDevice(id) : null;
    if (!device) throw new ApiError(401, 'Tracker necunoscut. Asociază-l din aplicație.');
    if (!headers.signature) throw new ApiError(401, 'Cerere nesemnată.');
    const publicKey = iot.importPublicKey(device.public_key);
    if (!iot.verifyRequestSignature(publicKey, method, routePath, headers, req.rawBody)) {
      throw new ApiError(401, 'Semnătura trackerului nu este validă.');
    }
    // dupa semnatura (altfel oricine ar putea "consuma" nonce-uri)
    const problem = replay.check(device.id, headers.time, headers.nonce);
    if (problem) throw new ApiError(401, problem);
    if (device.status === 'REVOKED') throw new ApiError(403, 'Trackerul a fost revocat de proprietar.', { revoked: true });
    device.last_seen_at = new Date().toISOString();
    return device;
  }

  router.add('GET', '/device/status', ({ req }) => {
    const device = authenticateDevice(req, 'GET', '/device/status');
    store.save();
    return { status: 200, body: { id: device.id, status: device.status } };
  });

  router.add('POST', '/device/visits', ({ req, body }) => {
    const device = authenticateDevice(req, 'POST', '/device/visits');
    const latitude = Number(body.latitude);
    const longitude = Number(body.longitude);
    if (!Number.isFinite(latitude) || Math.abs(latitude) > 90 || !Number.isFinite(longitude) || Math.abs(longitude) > 180) {
      throw new ApiError(400, 'Coordonate invalide.');
    }
    const visit = {
      id: `${device.id}-${Date.now()}`,
      device_id: device.id,
      owner_id: device.owner_id,
      latitude,
      longitude,
      place: typeof body.place === 'string' ? body.place.trim().slice(0, 160) : '',
      at: new Date().toISOString(),
    };
    store.data.deviceVisits.push(visit);
    // tinem doar ultimele locuri ale fiecarui tracker
    const own = store.data.deviceVisits.filter((v) => v.device_id === device.id);
    if (own.length > MAX_VISITS_PER_DEVICE) {
      const drop = new Set(own.slice(0, own.length - MAX_VISITS_PER_DEVICE).map((v) => v.id));
      store.data.deviceVisits = store.data.deviceVisits.filter((v) => !drop.has(v.id));
    }
    store.save();
    return { status: 201, body: visit };
  });
}

module.exports = { registerDeviceRoutes };
