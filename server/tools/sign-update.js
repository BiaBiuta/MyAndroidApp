'use strict';

/**
 * Unealta "producatorului" trackerului: semneaza actualizarile de configurare / firmware.
 *
 *   node tools/sign-update.js init
 *        creeaza cheia producatorului (tools/vendor-private.pem – SECRETA, nu se pune pe GitHub)
 *        si cheia publica (device/vendor-public.pem + textul de copiat in aplicatie)
 *   node tools/sign-update.js sign --version 1.1.0 --interval 15 --gps HIGH_ACCURACY --notes "..." [--out updates/x.json]
 *        creeaza un manifest semnat
 *   node tools/sign-update.js tamper updates/x.json --out updates/y.json
 *        copie MODIFICATA dupa semnare (pentru demonstratie: aplicatia trebuie s-o refuze)
 *   node tools/sign-update.js verify updates/x.json
 *        verifica un manifest cu cheia publica
 *
 * Serverul doar distribuie fisierele din updates/; nu are cheia privata, deci nu poate crea
 * o actualizare acceptata. Aplicatia si trackerul au doar cheia PUBLICA a producatorului.
 */

const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const iot = require('../src/iot');
const { MODEL } = require('../device/tracker');

const PRIVATE_FILE = path.join(__dirname, 'vendor-private.pem');
const PUBLIC_FILE = path.join(__dirname, '..', 'device', 'vendor-public.pem');

const args = process.argv.slice(2);
const command = args[0];
const option = (name, fallback) => {
  const i = args.indexOf(`--${name}`);
  return i >= 0 ? args[i + 1] : fallback;
};

function printPublicKeyForApp(publicKey) {
  const base64 = iot.b64(publicKey.export({ type: 'spki', format: 'der' }));
  console.log('Cheia publică a producătorului (pentru VendorKey.kt din aplicație):');
  console.log(base64);
}

function sign(payloadObject, privateKey) {
  const payload = JSON.stringify(payloadObject);
  const signature = crypto.sign('sha256', Buffer.from(payload, 'utf8'), privateKey);
  return { keyId: 'wl-vendor-1', payload, signature: iot.b64(signature) };
}

function write(file, manifest) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(manifest, null, 2) + '\n');
  console.log(`Scris: ${path.relative(process.cwd(), file)}`);
}

switch (command) {
  case 'init': {
    if (fs.existsSync(PRIVATE_FILE)) {
      console.log('Cheia producătorului există deja (tools/vendor-private.pem).');
      printPublicKeyForApp(crypto.createPublicKey(fs.readFileSync(PRIVATE_FILE)));
      break;
    }
    const { privateKey, publicKey } = iot.generateKeyPair();
    fs.writeFileSync(PRIVATE_FILE, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
    fs.writeFileSync(PUBLIC_FILE, publicKey.export({ type: 'spki', format: 'pem' }));
    console.log('Cheie nouă creată. Păstrează tools/vendor-private.pem secret (este în .gitignore).');
    printPublicKeyForApp(publicKey);
    break;
  }
  case 'sign': {
    const privateKey = crypto.createPrivateKey(fs.readFileSync(PRIVATE_FILE));
    const version = option('version');
    if (!version) throw new Error('Lipsește --version');
    const manifest = sign(
      {
        model: option('model', MODEL),
        version,
        releasedAt: new Date().toISOString().slice(0, 10),
        notes: option('notes', `Versiunea ${version}`),
        config: {
          reportIntervalSeconds: Number(option('interval', 20)),
          gpsMode: option('gps', 'BALANCED'),
        },
      },
      privateKey,
    );
    write(path.resolve(option('out', path.join(__dirname, '..', 'updates', `wlt1-${version}.json`))), manifest);
    break;
  }
  case 'tamper': {
    const source = JSON.parse(fs.readFileSync(args[1], 'utf8'));
    const payload = JSON.parse(source.payload);
    // modificare "rau intentionata" dupa semnare: raportare la fiecare secunda (consum de baterie)
    payload.version = option('version', '9.9.9');
    payload.notes = 'Versiune modificată după semnare (test: trebuie refuzată)';
    payload.config = { ...payload.config, reportIntervalSeconds: 1 };
    const tampered = { ...source, payload: JSON.stringify(payload) };
    write(path.resolve(option('out', args[1].replace(/\.json$/, '-modificat.json'))), tampered);
    break;
  }
  case 'verify': {
    const manifest = JSON.parse(fs.readFileSync(args[1], 'utf8'));
    const result = iot.verifyUpdate(manifest, crypto.createPublicKey(fs.readFileSync(PUBLIC_FILE)), {
      model: MODEL,
      currentVersion: option('current', '0.0.0'),
    });
    console.log(result.ok ? `Semnătură VALIDĂ: ${result.update.version}` : `REFUZAT: ${result.reason}`);
    break;
  }
  default:
    console.log('Comenzi: init | sign --version X [--interval N --gps MOD --notes TEXT] | tamper FISIER | verify FISIER');
}
