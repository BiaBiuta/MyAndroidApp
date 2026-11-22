'use strict';

/**
 * Criptografia comuna pentru trackerul WanderLog (dispozitivul IoT simulat), server si teste.
 * Aplicatia Android are aceleasi formule in iot/crypto/PairingCrypto.kt.
 *
 *  - chei EC P-256; cheile publice circula ca Base64 de SPKI (DER);
 *  - asocierea: ECDH + angajament (commitment) + cod de 6 cifre aratat pe tracker (out-of-band);
 *  - comenzile aplicatie -> tracker: HMAC-SHA256 cu cheia de asociere K;
 *  - cererile tracker -> server: semnatura ECDSA cu cheia proprie a trackerului (per dispozitiv);
 *  - actualizarile: semnate ECDSA de producator; aplicatia si trackerul le verifica.
 */

const crypto = require('node:crypto');

const PAIR_LABEL = 'WL-PAIR-v1';
const COMMIT_LABEL = 'WL-COMMIT';
const KEY_INFO = 'WL-PAIRING-KEY';
const APP_CONFIRM = 'WL-APP-CONFIRM';
const DEVICE_CONFIRM = 'WL-DEVICE-CONFIRM';
/** Cat de veche poate fi o cerere semnata (protectie la reluare, impreuna cu nonce-ul). */
const MAX_CLOCK_SKEW_MS = 5 * 60 * 1000;

const b64 = (buffer) => Buffer.from(buffer).toString('base64');
const unb64 = (text) => Buffer.from(String(text || ''), 'base64');
const sha256 = (...parts) => {
  const h = crypto.createHash('sha256');
  for (const p of parts) h.update(p);
  return h.digest();
};
const hmac = (key, ...parts) => {
  const h = crypto.createHmac('sha256', key);
  for (const p of parts) h.update(p);
  return h.digest();
};

function generateKeyPair() {
  return crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
}

function publicKeyDer(publicKey) {
  return publicKey.export({ type: 'spki', format: 'der' });
}

/** Cheie publica P-256 din Base64 (SPKI); arunca eroare daca nu e o cheie EC P-256 valida. */
function importPublicKey(base64) {
  const key = crypto.createPublicKey({ key: unb64(base64), format: 'der', type: 'spki' });
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new Error('Cheia trebuie sa fie EC P-256.');
  }
  return key;
}

/** Identificatorul unui tracker = amprenta cheii lui publice (nu poate fi ales de altcineva). */
function deviceIdFor(publicKeyDerBytes) {
  return 'WLT-' + sha256(publicKeyDerBytes).subarray(0, 5).toString('hex').toUpperCase();
}

// ---------- Asocierea ----------

function commitment(deviceNonce, appPubDer, devPubDer) {
  return hmac(deviceNonce, COMMIT_LABEL, appPubDer, devPubDer);
}

function transcript(appPubDer, devPubDer, appNonce, deviceNonce) {
  return sha256(PAIR_LABEL, appPubDer, devPubDer, appNonce, deviceNonce);
}

/** Codul de 6 cifre aratat pe tracker; aplicatia il calculeaza si ea si cere utilizatorului sa-l tasteze. */
function pairingCode(transcriptBytes) {
  return String(transcriptBytes.readUInt32BE(0) % 1_000_000).padStart(6, '0');
}

function deriveKey(privateKey, peerPublicKey, transcriptBytes) {
  const shared = crypto.diffieHellman({ privateKey, publicKey: peerPublicKey });
  return Buffer.from(crypto.hkdfSync('sha256', shared, transcriptBytes, KEY_INFO, 32));
}

const appConfirmMac = (key, transcriptBytes, code) => hmac(key, APP_CONFIRM, transcriptBytes, code);
const deviceConfirmMac = (key, transcriptBytes, identityPubDer) =>
  hmac(key, DEVICE_CONFIRM, transcriptBytes, identityPubDer);

// ---------- Cereri autentificate ----------

/** Textul semnat / autentificat al unei cereri: metoda, calea, ora, nonce-ul si hash-ul corpului. */
function canonicalRequest(method, path, time, nonce, rawBody) {
  const bodyHash = sha256(rawBody || Buffer.alloc(0)).toString('hex');
  return `${method.toUpperCase()}\n${path}\n${time}\n${nonce}\n${bodyHash}`;
}

function signRequest(privateKey, method, path, rawBody) {
  const time = String(Date.now());
  const nonce = crypto.randomBytes(12).toString('base64url');
  const signature = crypto.sign('sha256', Buffer.from(canonicalRequest(method, path, time, nonce, rawBody)), privateKey);
  return { 'X-WL-Time': time, 'X-WL-Nonce': nonce, 'X-WL-Signature': b64(signature) };
}

function verifyRequestSignature(publicKey, method, path, headers, rawBody) {
  const text = canonicalRequest(method, path, headers.time, headers.nonce, rawBody);
  try {
    return crypto.verify('sha256', Buffer.from(text), publicKey, unb64(headers.signature));
  } catch {
    return false;
  }
}

function macRequest(key, method, path, rawBody) {
  const time = String(Date.now());
  const nonce = crypto.randomBytes(12).toString('base64url');
  const mac = hmac(key, canonicalRequest(method, path, time, nonce, rawBody));
  return { 'X-WL-Time': time, 'X-WL-Nonce': nonce, 'X-WL-Mac': b64(mac) };
}

function verifyRequestMac(key, method, path, headers, rawBody) {
  const expected = hmac(key, canonicalRequest(method, path, headers.time, headers.nonce, rawBody));
  const given = unb64(headers.mac);
  return given.length === expected.length && crypto.timingSafeEqual(given, expected);
}

/**
 * Memoria anti-reluare: o cerere e acceptata o singura data (nonce) si doar daca e recenta (ora).
 */
class ReplayGuard {
  constructor(windowMs = MAX_CLOCK_SKEW_MS) {
    this.windowMs = windowMs;
    this.seen = new Map();
  }

  /** null = in regula; altfel motivul respingerii. */
  check(scope, time, nonce) {
    const now = Date.now();
    const t = Number(time);
    if (!Number.isFinite(t) || Math.abs(now - t) > this.windowMs) return 'Ora cererii nu este validă (ceas desincronizat sau cerere veche).';
    if (typeof nonce !== 'string' || nonce.length < 8 || nonce.length > 64) return 'Cerere fără nonce valid.';
    for (const [k, expires] of this.seen) if (expires < now) this.seen.delete(k);
    const id = `${scope}:${nonce}`;
    if (this.seen.has(id)) return 'Cererea a mai fost primită o dată (reluare respinsă).';
    this.seen.set(id, now + this.windowMs * 2);
    return null;
  }
}

// ---------- Actualizari semnate de producator ----------

/** Compara versiuni "1.2.10" > "1.2.9". */
function compareVersions(a, b) {
  const pa = String(a).split('.').map((n) => parseInt(n, 10) || 0);
  const pb = String(b).split('.').map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d !== 0) return d > 0 ? 1 : -1;
  }
  return 0;
}

/**
 * Verifica un manifest de actualizare: semnatura producatorului (peste textul exact "payload"),
 * modelul si versiunea (nu se accepta o versiune mai veche = rollback).
 * Intoarce { ok: true, update } sau { ok: false, reason }.
 */
function verifyUpdate(manifest, vendorPublicKey, { model, currentVersion }) {
  if (!manifest || typeof manifest.payload !== 'string' || typeof manifest.signature !== 'string') {
    return { ok: false, reason: 'Actualizarea nu are formatul așteptat.' };
  }
  let valid = false;
  try {
    valid = crypto.verify('sha256', Buffer.from(manifest.payload, 'utf8'), vendorPublicKey, unb64(manifest.signature));
  } catch {
    valid = false;
  }
  if (!valid) return { ok: false, reason: 'Semnătura producătorului nu este validă.' };
  let update;
  try {
    update = JSON.parse(manifest.payload);
  } catch {
    return { ok: false, reason: 'Conținutul actualizării nu poate fi citit.' };
  }
  if (update.model !== model) return { ok: false, reason: `Actualizarea este pentru alt model (${update.model}).` };
  if (compareVersions(update.version, currentVersion) <= 0) {
    return { ok: false, reason: `Versiunea ${update.version} nu este mai nouă decât ${currentVersion}.` };
  }
  return { ok: true, update };
}

module.exports = {
  MAX_CLOCK_SKEW_MS,
  b64,
  unb64,
  sha256,
  hmac,
  generateKeyPair,
  publicKeyDer,
  importPublicKey,
  deviceIdFor,
  commitment,
  transcript,
  pairingCode,
  deriveKey,
  appConfirmMac,
  deviceConfirmMac,
  canonicalRequest,
  signRequest,
  verifyRequestSignature,
  macRequest,
  verifyRequestMac,
  ReplayGuard,
  compareVersions,
  verifyUpdate,
};
