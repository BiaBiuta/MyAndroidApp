'use strict';

/**
 * WanderLog Tracker – dispozitivul IoT simulat (un tracker GPS de calatorie).
 *
 * Ruleaza pe calculator, langa server, si e accesibil din emulator la http://10.0.2.2:4000.
 *
 *   node device/tracker.js            porneste trackerul
 *   apoi in consola:  p = modul de asociere (2 minute)   s = stare   r = resetare   q = iesire
 *
 * Securitate:
 *  - asocierea NU e o conexiune deschisa: merge doar in modul de asociere (ca butonul fizic
 *    al unui dispozitiv), cu schimb de chei ECDH + angajament si un cod de 6 cifre afisat AICI,
 *    pe care utilizatorul il tasteaza in aplicatie (out-of-band). Fara cod corect nu exista cheie;
 *  - comenzile aplicatiei (stare, actualizare, dezasociere) sunt autentificate cu HMAC (cheia K);
 *  - catre server trackerul semneaza fiecare cerere cu cheia lui proprie (ECDSA, per dispozitiv);
 *    daca proprietarul l-a revocat, serverul raspunde 403 si trackerul se opreste;
 *  - o actualizare se instaleaza doar daca semnatura producatorului e valida (verificata si aici,
 *    nu doar in aplicatie).
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const readline = require('node:readline');
const iot = require('../src/iot');

const MODEL = 'WLT-1';
const PAIRING_WINDOW_MS = 2 * 60 * 1000;
const MAX_CONFIRM_ATTEMPTS = 3;

/** Traseul simulat: locurile prin care "trece" trackerul. */
const ROUTE = [
  { place: 'Piața Unirii, Cluj-Napoca', latitude: 46.7694, longitude: 23.5899 },
  { place: 'Cheile Turzii, Cluj', latitude: 46.5636, longitude: 23.6803 },
  { place: 'Salina Turda, Turda', latitude: 46.5877, longitude: 23.7873 },
  { place: 'Cetatea Alba Carolina, Alba Iulia', latitude: 46.0677, longitude: 23.5703 },
  { place: 'Transfăgărășan, Bâlea Lac', latitude: 45.6037, longitude: 24.6166 },
  { place: 'Piața Mare, Sibiu', latitude: 45.7972, longitude: 24.1520 },
  { place: 'Biserica Neagră, Brașov', latitude: 45.6408, longitude: 25.5884 },
  { place: 'Castelul Peleș, Sinaia', latitude: 45.3599, longitude: 25.5427 },
];

function createTracker(options = {}) {
  const settings = {
    port: Number(options.port ?? process.env.TRACKER_PORT ?? 4000),
    host: options.host ?? '0.0.0.0',
    serverUrl: options.serverUrl ?? process.env.WANDERLOG_SERVER ?? 'http://localhost:3000',
    dataDir: options.dataDir ?? path.join(__dirname, 'data'),
    vendorKeyFile: options.vendorKeyFile ?? path.join(__dirname, 'vendor-public.pem'),
    log: options.log ?? ((...args) => console.log(...args)),
    reportEveryMs: options.reportEveryMs,
  };
  const stateFile = path.join(settings.dataDir, 'tracker.json');
  const vendorPublicKey = crypto.createPublicKey(fs.readFileSync(settings.vendorKeyFile));
  const commandReplay = new iot.ReplayGuard();
  const log = settings.log;

  // ---------- Starea trackerului (pe "memoria" lui) ----------

  function newIdentity() {
    const { privateKey } = iot.generateKeyPair();
    return {
      identityPrivatePem: privateKey.export({ type: 'pkcs8', format: 'pem' }),
      firmware: '1.0.0',
      config: { reportIntervalSeconds: 20, gpsMode: 'BALANCED' },
      pairing: null,
      revoked: false,
      routeIndex: 0,
    };
  }

  function load() {
    try {
      return JSON.parse(fs.readFileSync(stateFile, 'utf8'));
    } catch {
      return newIdentity();
    }
  }

  let state = load();
  function save() {
    fs.mkdirSync(settings.dataDir, { recursive: true });
    fs.writeFileSync(stateFile, JSON.stringify(state, null, 2), { mode: 0o600 });
  }
  save();

  let identityKey = crypto.createPrivateKey(state.identityPrivatePem);
  let identityPubDer = iot.publicKeyDer(crypto.createPublicKey(identityKey));
  let deviceId = iot.deviceIdFor(identityPubDer);

  // ---------- Asocierea ----------

  let pairingOpenUntil = 0;
  let session = null; // { id, appPubDer, devKeys, devPubDer, deviceNonce, transcript, code, attempts, expires }

  function openPairing() {
    if (state.pairing) {
      log('Trackerul este deja asociat. Resetează-l (r) ca să-l asociezi cu alt telefon.');
      return false;
    }
    pairingOpenUntil = Date.now() + PAIRING_WINDOW_MS;
    session = null;
    log('Modul de asociere este PORNIT pentru 2 minute. În aplicație: Trackere → Asociază un tracker.');
    return true;
  }

  function pairingOpen() {
    return !state.pairing && Date.now() < pairingOpenUntil;
  }

  function currentSession(id) {
    if (!session || session.id !== id || Date.now() > session.expires) {
      throw httpError(410, 'Sesiunea de asociere a expirat. Începe din nou.');
    }
    return session;
  }

  function showCode(code) {
    const pretty = `${code.slice(0, 3)} ${code.slice(3)}`;
    log('');
    log('  ╔════════════════════════════════╗');
    log(`  ║  COD DE ASOCIERE:   ${pretty}    ║`);
    log('  ║  Introdu-l în aplicație.       ║');
    log('  ╚════════════════════════════════╝');
    log('');
  }

  // ---------- Comenzi autentificate de la aplicatie ----------

  function authenticateCommand(req, method, routePath) {
    if (!state.pairing) throw httpError(403, 'Trackerul nu este asociat.');
    const headers = { time: req.headers['x-wl-time'], nonce: req.headers['x-wl-nonce'], mac: req.headers['x-wl-mac'] };
    const key = iot.unb64(state.pairing.key);
    if (!headers.mac || !iot.verifyRequestMac(key, method, routePath, headers, req.rawBody)) {
      throw httpError(401, 'Comandă neautentificată: telefonul nu este cel asociat.');
    }
    const problem = commandReplay.check('app', headers.time, headers.nonce);
    if (problem) throw httpError(401, problem);
  }

  function publicState() {
    return {
      deviceId,
      model: MODEL,
      firmware: state.firmware,
      config: state.config,
      paired: Boolean(state.pairing),
      revoked: state.revoked,
      lastReport: state.lastReport || null,
    };
  }

  // ---------- Rutele HTTP ale trackerului ----------

  const routes = {
    'GET /info': () => ({
      deviceId,
      name: 'WanderLog Tracker',
      model: MODEL,
      firmware: state.firmware,
      paired: Boolean(state.pairing),
      pairingMode: pairingOpen(),
    }),

    // 1) telefonul trimite cheia lui ECDH; trackerul raspunde cu cheia lui si un ANGAJAMENT
    //    la nonce-ul sau (nu-l mai poate schimba dupa ce vede nonce-ul telefonului)
    'POST /pair/start': ({ body }) => {
      if (state.pairing) throw httpError(409, 'Trackerul este deja asociat cu un telefon.');
      if (!pairingOpen()) {
        throw httpError(403, 'Trackerul nu este în modul de asociere. Apasă „p” în consola trackerului.');
      }
      let appPub;
      try {
        appPub = iot.importPublicKey(body.appPublicKey);
      } catch {
        throw httpError(400, 'Cheia telefonului nu este validă.');
      }
      const devKeys = iot.generateKeyPair();
      const devPubDer = iot.publicKeyDer(devKeys.publicKey);
      const appPubDer = iot.publicKeyDer(appPub);
      const deviceNonce = crypto.randomBytes(16);
      session = {
        id: crypto.randomBytes(12).toString('base64url'),
        appPub,
        appPubDer,
        devKeys,
        devPubDer,
        deviceNonce,
        attempts: 0,
        expires: Date.now() + PAIRING_WINDOW_MS,
      };
      return {
        sessionId: session.id,
        devicePublicKey: iot.b64(devPubDer),
        commitment: iot.b64(iot.commitment(deviceNonce, appPubDer, devPubDer)),
      };
    },

    // 2) telefonul trimite nonce-ul lui; trackerul il dezvaluie pe al sau si AFISEAZA codul
    'POST /pair/nonce': ({ body }) => {
      const s = currentSession(body.sessionId);
      if (s.transcript) throw httpError(409, 'Nonce-ul a fost deja trimis pentru această sesiune.');
      const appNonce = iot.unb64(body.appNonce);
      if (appNonce.length !== 16) throw httpError(400, 'Nonce invalid.');
      s.transcript = iot.transcript(s.appPubDer, s.devPubDer, appNonce, s.deviceNonce);
      s.code = iot.pairingCode(s.transcript);
      s.key = iot.deriveKey(s.devKeys.privateKey, s.appPub, s.transcript);
      showCode(s.code);
      return { deviceNonce: iot.b64(s.deviceNonce) };
    },

    // 3) telefonul dovedeste ca are aceeasi cheie SI codul de pe ecran; abia acum se asociaza
    'POST /pair/confirm': ({ body }) => {
      const s = currentSession(body.sessionId);
      if (!s.transcript) throw httpError(409, 'Trimite întâi nonce-ul.');
      const expected = iot.appConfirmMac(s.key, s.transcript, s.code);
      const given = iot.unb64(body.mac);
      if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) {
        s.attempts += 1;
        if (s.attempts >= MAX_CONFIRM_ATTEMPTS) {
          session = null;
          pairingOpenUntil = 0;
          log('Prea multe coduri greșite: asocierea a fost anulată.');
          throw httpError(403, 'Prea multe încercări greșite. Pornește din nou modul de asociere pe tracker.');
        }
        throw httpError(401, 'Codul nu se potrivește.', { attemptsLeft: MAX_CONFIRM_ATTEMPTS - s.attempts });
      }
      state.pairing = { key: iot.b64(s.key), pairedAt: new Date().toISOString() };
      state.revoked = false;
      save();
      const reply = {
        deviceId,
        model: MODEL,
        firmware: state.firmware,
        identityPublicKey: iot.b64(identityPubDer),
        mac: iot.b64(iot.deviceConfirmMac(s.key, s.transcript, identityPubDer)),
      };
      session = null;
      pairingOpenUntil = 0;
      log(`Asociat cu succes (${deviceId}). Trackerul începe să trimită locurile la server.`);
      scheduleReport(3000);
      return reply;
    },

    'GET /state': ({ req }) => {
      authenticateCommand(req, 'GET', '/state');
      return publicState();
    },

    // Actualizare: verificata si aici cu cheia producatorului (aparare in adancime)
    'POST /update': ({ req, body }) => {
      authenticateCommand(req, 'POST', '/update');
      const result = iot.verifyUpdate(body.manifest, vendorPublicKey, { model: MODEL, currentVersion: state.firmware });
      if (!result.ok) {
        log(`Actualizare REFUZATĂ: ${result.reason}`);
        throw httpError(422, `Trackerul a refuzat actualizarea: ${result.reason}`);
      }
      state.firmware = result.update.version;
      state.config = { ...state.config, ...(result.update.config || {}) };
      save();
      log(`Actualizare instalată: versiunea ${state.firmware}, configurare ${JSON.stringify(state.config)}`);
      scheduleReport();
      return publicState();
    },

    'POST /unpair': ({ req }) => {
      authenticateCommand(req, 'POST', '/unpair');
      state.pairing = null;
      save();
      log('Telefonul a dezasociat trackerul.');
      return { paired: false };
    },
  };

  const server = http.createServer((req, res) => {
    const { pathname } = new URL(req.url, 'http://localhost');
    const chunks = [];
    let size = 0;
    req.on('data', (c) => {
      size += c.length;
      if (size > 64 * 1024) req.destroy();
      else chunks.push(c);
    });
    req.on('end', () => {
      req.rawBody = Buffer.concat(chunks);
      let status = 200;
      let payload;
      try {
        const handler = routes[`${req.method} ${pathname}`];
        if (!handler) throw httpError(404, 'Comandă necunoscută.');
        let body = {};
        if (req.rawBody.length) {
          try {
            body = JSON.parse(req.rawBody.toString('utf8'));
          } catch {
            throw httpError(400, 'JSON invalid.');
          }
        }
        payload = handler({ req, body });
      } catch (error) {
        status = error.status || 500;
        payload = { message: error.status ? error.message : 'Eroare internă a trackerului.', ...(error.extra || {}) };
        if (!error.status) console.error(error);
      }
      const text = JSON.stringify(payload);
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(text) });
      res.end(text);
    });
  });

  // ---------- Raportarea locurilor catre server (semnata cu cheia trackerului) ----------

  let timer = null;
  function scheduleReport(delayMs) {
    clearTimeout(timer);
    if (!state.pairing || state.revoked) return;
    const every = settings.reportEveryMs ?? state.config.reportIntervalSeconds * 1000;
    timer = setTimeout(report, delayMs ?? every);
    timer.unref?.();
  }

  async function signedFetch(method, routePath, body) {
    const raw = body ? Buffer.from(JSON.stringify(body)) : Buffer.alloc(0);
    const headers = {
      'X-WL-Device': deviceId,
      ...iot.signRequest(identityKey, method, routePath, raw),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    };
    const res = await fetch(settings.serverUrl + routePath, { method, headers, body: body ? raw : undefined });
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  }

  async function report() {
    const stop = ROUTE[state.routeIndex % ROUTE.length];
    try {
      const res = await signedFetch('POST', '/device/visits', stop);
      if (res.status === 201) {
        state.routeIndex += 1;
        state.lastReport = { place: stop.place, at: new Date().toISOString() };
        save();
        log(`→ Loc trimis la server: ${stop.place}`);
      } else if (res.status === 403 && res.body?.revoked) {
        // proprietarul a revocat trackerul (ex. pierdut): uitam cheia de asociere si ne oprim
        state.revoked = true;
        state.pairing = null;
        save();
        log('✖ Serverul a respins trackerul: a fost REVOCAT de proprietar. Trackerul nu mai trimite nimic.');
        log('  Pentru a-l folosi din nou: r (resetare = cheie nouă), apoi asociere din aplicație.');
        return;
      } else {
        log(`Serverul a răspuns ${res.status}: ${res.body?.message || ''}`);
      }
    } catch (error) {
      log(`Serverul nu este disponibil (${settings.serverUrl}). Reîncerc mai târziu.`);
    }
    scheduleReport();
  }

  /** Resetare din fabrica: cheie noua (id nou), fara asociere, firmware initial. */
  function reset() {
    clearTimeout(timer);
    state = newIdentity();
    save();
    identityKey = crypto.createPrivateKey(state.identityPrivatePem);
    identityPubDer = iot.publicKeyDer(crypto.createPublicKey(identityKey));
    deviceId = iot.deviceIdFor(identityPubDer);
    session = null;
    pairingOpenUntil = 0;
    log(`Tracker resetat. Id nou: ${deviceId}`);
  }

  function start() {
    return new Promise((resolve) => {
      server.listen(settings.port, settings.host, () => {
        log(`WanderLog Tracker ${deviceId} (model ${MODEL}, firmware ${state.firmware})`);
        log(`  Adresa pentru emulator: 10.0.2.2:${server.address().port}`);
        log(`  Server WanderLog: ${settings.serverUrl}`);
        log(state.pairing ? '  Stare: asociat' : '  Stare: neasociat – apasă „p” + Enter pentru modul de asociere');
        scheduleReport(2000);
        resolve(server.address().port);
      });
    });
  }

  function stop() {
    clearTimeout(timer);
    return new Promise((resolve) => server.close(() => resolve()));
  }

  return {
    start,
    stop,
    openPairing,
    reset,
    report,
    get deviceId() {
      return deviceId;
    },
    get state() {
      return state;
    },
    get lastCode() {
      return session?.code;
    },
  };
}

function httpError(status, message, extra) {
  const error = new Error(message);
  error.status = status;
  error.extra = extra;
  return error;
}

// ---------- Pornire din linia de comanda ----------

if (require.main === module) {
  const args = process.argv.slice(2);
  const value = (name) => {
    const i = args.indexOf(name);
    return i >= 0 ? args[i + 1] : undefined;
  };
  const tracker = createTracker({ port: value('--port'), serverUrl: value('--server') });
  tracker.start().then(() => {
    if (args.includes('--pair')) tracker.openPairing();
    const rl = readline.createInterface({ input: process.stdin });
    rl.on('line', (line) => {
      const command = line.trim().toLowerCase();
      if (command === 'p') tracker.openPairing();
      else if (command === 's') console.log(JSON.stringify({ id: tracker.deviceId, ...tracker.state, identityPrivatePem: '(secret)', pairing: tracker.state.pairing ? '(asociat)' : null }, null, 2));
      else if (command === 'r') tracker.reset();
      else if (command === 'q') tracker.stop().then(() => process.exit(0));
      else if (command) console.log('Comenzi: p = asociere, s = stare, r = resetare, q = ieșire');
    });
  });
}

module.exports = { createTracker, MODEL, ROUTE };
