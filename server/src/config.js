'use strict';

const path = require('node:path');

/** Setarile serverului; se pot schimba din variabile de mediu (ex. PORT=4000 node server.js). */
module.exports = {
  port: Number(process.env.PORT || 3000),
  // 0.0.0.0 = accesibil si din emulator (10.0.2.2) si de pe telefon, din aceeasi retea Wi-Fi
  host: process.env.HOST || '0.0.0.0',
  dataDir: process.env.DATA_DIR || path.join(__dirname, '..', 'data'),
  // Cat e valabil un token: dupa expirare, aplicatia cere din nou autentificarea
  tokenTtlSeconds: Number(process.env.TOKEN_TTL_SECONDS || 7 * 24 * 60 * 60),
  // Corpul unei cereri poate contine o poza in Base64, deci limita e generoasa
  maxBodyBytes: 12 * 1024 * 1024,
  // Conturile care primesc rolul ADMIN la inregistrare (la fel ca in aplicatie)
  adminEmails: (process.env.ADMIN_EMAILS || 'admin@wanderlog.ro')
    .split(',')
    .map((e) => e.trim().toLowerCase())
    .filter(Boolean),
  // Limitarea incercarilor de login (protectie impotriva ghicirii parolei)
  login: { maxAttempts: 5, baseLockMs: 30_000, maxLockMs: 15 * 60_000 },
};
