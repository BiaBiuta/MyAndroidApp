'use strict';

// Teste pentru trackerul WanderLog (IoT): asociere cu cod, chei per dispozitiv, revocare,
// actualizari semnate. Pornesc serverul si trackerul simulat pe porturi aleatoare.
// "Telefonul" e simulat aici cu aceleasi formule ca aplicatia (PairingCrypto.kt).

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../src/app');
const { createTracker } = require('../device/tracker');
const iot = require('../src/iot');

const STRONG = 'Calatorie#2026!';
let app;
let base;
let tracker;
let trackerBase;
let dirs = [];

before(async () => {
  const serverDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wl-srv-'));
  const trackerDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wl-dev-'));
  dirs = [serverDir, trackerDir];
  app = createApp({ dataDir: serverDir, quiet: true });
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${app.server.address().port}`;
  tracker = createTracker({ port: 0, host: '127.0.0.1', serverUrl: base, dataDir: trackerDir, log: () => {}, reportEveryMs: 3_600_000 });
  const port = await tracker.start();
  trackerBase = `http://127.0.0.1:${port}`;
});

after(async () => {
  await tracker.stop();
  await app.close();
  for (const d of dirs) fs.rmSync(d, { recursive: true, force: true });
});

async function call(url, method, { token, body, headers = {}, raw } = {}) {
  const data = raw ?? (body ? Buffer.from(JSON.stringify(body)) : undefined);
  const res = await fetch(url, {
    method,
    headers: {
      ...(data ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: data,
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

const api = (method, route, opts) => call(base + route, method, opts);
const dev = (method, route, opts) => call(trackerBase + route, method, opts);

async function signup(email) {
  const res = await api('POST', '/signup', { body: { email, password: STRONG } });
  assert.equal(res.status, 201);
  return res.body.token;
}

/** Partea telefonului din asociere (aceeasi ca in aplicatie). userTypes = codul tastat de utilizator. */
async function pairPhone(userTypes) {
  const appKeys = iot.generateKeyPair();
  const appPubDer = iot.publicKeyDer(appKeys.publicKey);
  const start = await dev('POST', '/pair/start', { body: { appPublicKey: iot.b64(appPubDer) } });
  if (start.status !== 200) return { start };
  const devPubDer = iot.unb64(start.body.devicePublicKey);
  const appNonce = crypto.randomBytes(16);
  const reveal = await dev('POST', '/pair/nonce', { body: { sessionId: start.body.sessionId, appNonce: iot.b64(appNonce) } });
  const deviceNonce = iot.unb64(reveal.body.deviceNonce);
  // angajamentul: trackerul nu si-a schimbat nonce-ul dupa ce l-a vazut pe al telefonului
  const commitOk = iot.commitment(deviceNonce, appPubDer, devPubDer).equals(iot.unb64(start.body.commitment));
  const transcript = iot.transcript(appPubDer, devPubDer, appNonce, deviceNonce);
  const expectedCode = iot.pairingCode(transcript);
  const code = typeof userTypes === 'function' ? userTypes(tracker.lastCode) : userTypes;
  const key = iot.deriveKey(appKeys.privateKey, crypto.createPublicKey({ key: devPubDer, format: 'der', type: 'spki' }), transcript);
  // aplicatia compara codul tastat cu cel calculat; trimite MAC-ul cu codul tastat
  const confirm = await dev('POST', '/pair/confirm', {
    body: { sessionId: start.body.sessionId, mac: iot.b64(iot.appConfirmMac(key, transcript, code)) },
  });
  let deviceMacOk = false;
  if (confirm.status === 200) {
    const identityDer = iot.unb64(confirm.body.identityPublicKey);
    deviceMacOk = iot.deviceConfirmMac(key, transcript, identityDer).equals(iot.unb64(confirm.body.mac));
  }
  return { start, commitOk, expectedCode, code, key, confirm, deviceMacOk };
}

let owner;
let pairing;

test('asocierea e refuzata cand trackerul nu e in modul de asociere', async () => {
  const res = await pairPhone('000000');
  assert.equal(res.start.status, 403);
});

test('cod gresit de 3 ori: asocierea e anulata', async () => {
  tracker.openPairing();
  for (let i = 0; i < 2; i++) {
    const r = await pairPhone((shown) => (shown === '111111' ? '222222' : '111111'));
    assert.equal(r.confirm.status, 401);
  }
  // fiecare pairPhone porneste o sesiune noua; 3 greseli in aceeasi sesiune o anuleaza
  const appKeys = iot.generateKeyPair();
  const start = await dev('POST', '/pair/start', { body: { appPublicKey: iot.b64(iot.publicKeyDer(appKeys.publicKey)) } });
  await dev('POST', '/pair/nonce', { body: { sessionId: start.body.sessionId, appNonce: iot.b64(crypto.randomBytes(16)) } });
  const statuses = [];
  for (let i = 0; i < 3; i++) {
    const r = await dev('POST', '/pair/confirm', { body: { sessionId: start.body.sessionId, mac: iot.b64(crypto.randomBytes(32)) } });
    statuses.push(r.status);
  }
  assert.deepEqual(statuses, [401, 401, 403]);
  assert.equal(tracker.state.pairing, null);
});

test('asociere reusita cu codul de pe tracker; angajament si confirmare verificate', async () => {
  tracker.openPairing();
  pairing = await pairPhone((shown) => shown);
  assert.equal(pairing.confirm.status, 200, JSON.stringify(pairing.confirm.body));
  assert.ok(pairing.commitOk, 'angajamentul trackerului');
  assert.equal(pairing.code, pairing.expectedCode, 'codul afisat = codul calculat de telefon');
  assert.ok(pairing.deviceMacOk, 'trackerul a dovedit ca are aceeasi cheie');
  assert.equal(pairing.confirm.body.deviceId, tracker.deviceId);
  // deja asociat: nimeni altcineva nu-l mai poate asocia
  tracker.openPairing();
  const again = await pairPhone('123456');
  assert.equal(again.start.status, 409);
});

test('comenzile catre tracker cer MAC-ul cheii de asociere; reluarea e respinsa', async () => {
  assert.equal((await dev('GET', '/state')).status, 401);
  const fake = iot.macRequest(crypto.randomBytes(32), 'GET', '/state', Buffer.alloc(0));
  assert.equal((await dev('GET', '/state', { headers: fake })).status, 401);
  const headers = iot.macRequest(pairing.key, 'GET', '/state', Buffer.alloc(0));
  const ok = await dev('GET', '/state', { headers });
  assert.equal(ok.status, 200);
  assert.equal(ok.body.paired, true);
  assert.equal((await dev('GET', '/state', { headers })).status, 401, 'aceeasi cerere a doua oara');
});

test('inregistrare pe server: cheie per dispozitiv, id legat de cheie, doar proprietarul il vede', async () => {
  owner = await signup('tracker-owner@example.com');
  const body = {
    id: pairing.confirm.body.deviceId,
    name: 'Trackerul meu',
    model: 'WLT-1',
    firmware: '1.0.0',
    publicKey: pairing.confirm.body.identityPublicKey,
  };
  const wrongId = await api('POST', '/devices', { token: owner, body: { ...body, id: 'WLT-0000000000' } });
  assert.equal(wrongId.status, 400);
  const created = await api('POST', '/devices', { token: owner, body });
  assert.equal(created.status, 201);
  assert.equal(created.body.status, 'ACTIVE');

  const other = await signup('altcineva@example.com');
  assert.equal((await api('GET', '/devices', { token: other })).body.length, 0);
  assert.equal((await api('GET', `/devices/${body.id}/visits`, { token: other })).status, 404);
  assert.equal((await api('POST', `/devices/${body.id}/revoke`, { token: other })).status, 404);
  assert.equal((await api('POST', '/devices', { token: other, body })).status, 409);
});

test('trackerul trimite locuri semnate; semnatura falsa si reluarea sunt respinse', async () => {
  await tracker.report();
  const visits = await api('GET', `/devices/${tracker.deviceId}/visits`, { token: owner });
  assert.equal(visits.body.length, 1);
  assert.equal(visits.body[0].place, 'Piața Unirii, Cluj-Napoca');

  const raw = Buffer.from(JSON.stringify({ latitude: 1, longitude: 2, place: 'Fals' }));
  const intruder = iot.generateKeyPair();
  const forged = { 'X-WL-Device': tracker.deviceId, ...iot.signRequest(intruder.privateKey, 'POST', '/device/visits', raw) };
  assert.equal((await api('POST', '/device/visits', { raw, headers: forged })).status, 401);

  const identity = crypto.createPrivateKey(tracker.state.identityPrivatePem);
  const signed = { 'X-WL-Device': tracker.deviceId, ...iot.signRequest(identity, 'POST', '/device/visits', raw) };
  assert.equal((await api('POST', '/device/visits', { raw, headers: signed })).status, 201);
  assert.equal((await api('POST', '/device/visits', { raw, headers: signed })).status, 401, 'reluare');
  // corp modificat cu aceeasi semnatura
  const changed = Buffer.from(JSON.stringify({ latitude: 9, longitude: 9, place: 'Modificat' }));
  const fresh = { 'X-WL-Device': tracker.deviceId, ...iot.signRequest(identity, 'POST', '/device/visits', raw) };
  assert.equal((await api('POST', '/device/visits', { raw: changed, headers: fresh })).status, 401);
});

test('actualizari: doar cea semnata de producator se instaleaza', async () => {
  const list = await api('GET', '/device-updates', { token: owner });
  assert.equal(list.status, 200);
  const byVersion = Object.fromEntries(list.body.map((m) => [JSON.parse(m.payload).version, m]));
  const install = (manifest) => {
    const raw = Buffer.from(JSON.stringify({ manifest }));
    return dev('POST', '/update', { raw, headers: iot.macRequest(pairing.key, 'POST', '/update', raw) });
  };
  const tampered = await install(byVersion['1.2.0']);
  assert.equal(tampered.status, 422);
  assert.match(tampered.body.message, /Semnătura/);
  const rollback = await install(byVersion['0.9.0']);
  assert.equal(rollback.status, 422);
  const ok = await install(byVersion['1.1.0']);
  assert.equal(ok.status, 200);
  assert.equal(ok.body.firmware, '1.1.0');
  assert.equal(ok.body.config.reportIntervalSeconds, 15);
  assert.equal((await install(byVersion['1.1.0'])).status, 422, 'aceeasi versiune nu se reinstaleaza');
});

test('revocare: trackerul pierdut e respins de server si se opreste', async () => {
  const revoked = await api('POST', `/devices/${tracker.deviceId}/revoke`, { token: owner });
  assert.equal(revoked.status, 200);
  assert.equal(revoked.body.status, 'REVOKED');
  await tracker.report();
  assert.equal(tracker.state.revoked, true);
  assert.equal(tracker.state.pairing, null, 'cheia de asociere a fost stearsa');
  const identity = crypto.createPrivateKey(tracker.state.identityPrivatePem);
  const raw = Buffer.from('{}');
  const res = await api('GET', '/device/status', {
    headers: { 'X-WL-Device': tracker.deviceId, ...iot.signRequest(identity, 'GET', '/device/status', Buffer.alloc(0)) },
  });
  assert.equal(res.status, 403);
  assert.ok(raw);
  // aceeasi cheie nu mai poate fi inregistrata
  const again = await api('POST', '/devices', {
    token: owner,
    body: { id: tracker.deviceId, publicKey: pairing.confirm.body.identityPublicKey },
  });
  assert.equal(again.status, 409);
});
