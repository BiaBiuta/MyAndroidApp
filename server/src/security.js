'use strict';

const crypto = require('node:crypto');

// ---------------------------------------------------------------------------
// Parole: scrypt cu salt aleator. Parola in clar nu se salveaza niciodata.
// Format: "scrypt$N$r$p$<salt base64>$<hash base64>"
// ---------------------------------------------------------------------------

const SCRYPT = { N: 16384, r: 8, p: 1, keyLength: 64 };

function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(password, salt, SCRYPT.keyLength, SCRYPT);
  return ['scrypt', SCRYPT.N, SCRYPT.r, SCRYPT.p, salt.toString('base64'), hash.toString('base64')].join('$');
}

function verifyPassword(password, stored) {
  try {
    const [algorithm, N, r, p, salt, hash] = String(stored).split('$');
    if (algorithm !== 'scrypt') return false;
    const expected = Buffer.from(hash, 'base64');
    const actual = crypto.scryptSync(password, Buffer.from(salt, 'base64'), expected.length, {
      N: Number(N),
      r: Number(r),
      p: Number(p),
    });
    return crypto.timingSafeEqual(actual, expected);
  } catch {
    return false;
  }
}

// Hash folosit cand contul nu exista: verificarea dureaza la fel, deci timpul
// de raspuns nu dezvaluie ce adrese de e-mail au cont.
const DUMMY_HASH = hashPassword('cont-inexistent-' + crypto.randomUUID());

// ---------------------------------------------------------------------------
// Regulile pentru o parola noua (aceleasi ca in aplicatie, PasswordPolicy.kt).
// Serverul le verifica si el: clientul nu e de incredere.
// ---------------------------------------------------------------------------

const COMMON_FRAGMENTS = [
  'password', 'parola', 'passw0rd', 'qwerty', 'azerty', 'asdf', 'zxcv', '123456', '12345',
  '654321', '111111', '000000', 'abc123', 'abcdef', 'admin', 'welcome', 'letmein',
  'iloveyou', 'monkey', 'dragon', 'football', 'baseball', 'superman', 'batman',
  'sunshine', 'princess', 'master', 'secret', 'login', 'romania', 'bucuresti',
  'wanderlog', 'android',
];

/** Intoarce lista regulilor neindeplinite (goala = parola acceptata). */
function passwordProblems(password, email) {
  const problems = [];
  const lower = password.toLowerCase();
  const emailName = String(email).trim().split('@')[0].toLowerCase();
  if (password.length < 12) problems.push('cel puțin 12 caractere');
  if (password.length > 64) problems.push('cel mult 64 de caractere');
  if (!/\p{Lu}/u.test(password)) problems.push('o literă mare');
  if (!/\p{Ll}/u.test(password)) problems.push('o literă mică');
  if (!/\p{Nd}/u.test(password)) problems.push('o cifră');
  if (!/[^\p{L}\p{Nd}\s]/u.test(password)) problems.push('un simbol');
  if (/(.)\1\1/u.test(password)) problems.push('fără 3 caractere identice la rând');
  if (emailName.length >= 3 && lower.includes(emailName)) problems.push('să nu conțină numele din e-mail');
  if (COMMON_FRAGMENTS.some((f) => lower.includes(f))) problems.push('să nu fie o parolă des folosită');
  return problems;
}

const EMAIL_PATTERN = /^[a-zA-Z0-9+._%-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9-]{0,64}(\.[a-zA-Z0-9][a-zA-Z0-9-]{0,25})+$/;

function isValidEmail(email) {
  return typeof email === 'string' && email.length <= 254 && EMAIL_PATTERN.test(email);
}

function normalizeEmail(email) {
  return String(email || '').trim().toLowerCase();
}

// ---------------------------------------------------------------------------
// JWT (HS256), semnat cu un secret pastrat doar pe server.
// ---------------------------------------------------------------------------

const base64url = (input) => Buffer.from(input).toString('base64url');

function signToken(claims, secret, ttlSeconds) {
  const now = Math.floor(Date.now() / 1000);
  const header = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64url(
    JSON.stringify({ ...claims, jti: crypto.randomUUID(), iat: now, exp: now + ttlSeconds }),
  );
  const signature = crypto.createHmac('sha256', secret).update(`${header}.${payload}`).digest('base64url');
  return `${header}.${payload}.${signature}`;
}

/** Intoarce continutul token-ului daca semnatura e corecta si nu a expirat, altfel null. */
function verifyToken(token, secret) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  const [header, payload, signature] = parts;
  try {
    const { alg } = JSON.parse(Buffer.from(header, 'base64url').toString('utf8'));
    if (alg !== 'HS256') return null; // nu acceptam "alg: none" sau alti algoritmi
    const expected = crypto.createHmac('sha256', secret).update(`${header}.${payload}`).digest();
    const actual = Buffer.from(signature, 'base64url');
    if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected)) return null;
    const claims = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (typeof claims.exp !== 'number' || claims.exp <= Math.floor(Date.now() / 1000)) return null;
    return claims;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Limitarea incercarilor de login: dupa N parole gresite pentru acelasi cont,
// login-ul se blocheaza temporar (30 s, apoi dublu la fiecare blocare, max 15 min).
// ---------------------------------------------------------------------------

class LoginLimiter {
  constructor({ maxAttempts, baseLockMs, maxLockMs }) {
    this.maxAttempts = maxAttempts;
    this.baseLockMs = baseLockMs;
    this.maxLockMs = maxLockMs;
    this.entries = new Map();
  }

  /** Milisecunde ramase din blocare (0 = se poate incerca). */
  remainingLockMs(key) {
    const entry = this.entries.get(key);
    return entry ? Math.max(0, entry.lockedUntil - Date.now()) : 0;
  }

  attemptsLeft(key) {
    const entry = this.entries.get(key);
    return this.maxAttempts - (entry ? entry.failures : 0);
  }

  /** Inregistreaza o incercare gresita; intoarce durata blocarii daca acum s-a blocat. */
  registerFailure(key) {
    const entry = this.entries.get(key) || { failures: 0, lockouts: 0, lockedUntil: 0 };
    entry.failures += 1;
    let lockMs = 0;
    if (entry.failures >= this.maxAttempts) {
      entry.lockouts += 1;
      entry.failures = 0;
      lockMs = Math.min(this.baseLockMs * 2 ** Math.min(entry.lockouts - 1, 5), this.maxLockMs);
      entry.lockedUntil = Date.now() + lockMs;
    }
    this.entries.set(key, entry);
    return lockMs;
  }

  registerSuccess(key) {
    this.entries.delete(key);
  }
}

module.exports = {
  hashPassword,
  verifyPassword,
  DUMMY_HASH,
  passwordProblems,
  isValidEmail,
  normalizeEmail,
  signToken,
  verifyToken,
  LoginLimiter,
};
