'use strict';

// Teste end-to-end: pornesc serverul pe un port aleator, cu o baza de date temporara.
// Rulare: npm test   (sau: node --test test/*.test.js)

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../src/app');

const STRONG = 'Calatorie#2026!';
let app;
let base;
let wsUrl;
let dataDir;

before(async () => {
  dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wanderlog-test-'));
  app = createApp({ dataDir, quiet: true, login: { maxAttempts: 3, baseLockMs: 2_000, maxLockMs: 10_000 } });
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  const { port } = app.server.address();
  base = `http://127.0.0.1:${port}`;
  wsUrl = `ws://127.0.0.1:${port}/`;
});

after(async () => {
  await app.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

async function api(method, route, { token, body } = {}) {
  const res = await fetch(base + route, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null, headers: res.headers };
}

async function signup(email, password = STRONG) {
  const res = await api('POST', '/signup', { body: { email, password } });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  return res.body;
}

test('inregistrare: cont nou, token si rol', async () => {
  const res = await signup('ana@example.com');
  assert.ok(res.token.split('.').length === 3, 'token JWT');
  assert.equal(res.user.email, 'ana@example.com');
  assert.equal(res.user.password, '');
  assert.equal(res.role, 'USER');
  assert.equal(res.provider, 'EMAIL');
  // parola nu apare nicaieri in raspuns, iar in baza de date e doar hash-ul
  const stored = app.store.findUserByEmail('ana@example.com');
  assert.match(stored.passwordHash, /^scrypt\$/);
  assert.ok(!JSON.stringify(res).includes(STRONG));
});

test('inregistrare: parola slaba, e-mail invalid, cont existent', async () => {
  const weak = await api('POST', '/signup', { body: { email: 'bob@example.com', password: 'parola123' } });
  assert.equal(weak.status, 400);
  assert.ok(weak.body.problems.length > 0);
  const badEmail = await api('POST', '/signup', { body: { email: 'bob@', password: STRONG } });
  assert.equal(badEmail.status, 400);
  const duplicate = await api('POST', '/signup', { body: { email: 'ANA@example.com', password: STRONG } });
  assert.equal(duplicate.status, 409);
});

test('login: corect, gresit, acelasi mesaj pentru cont inexistent', async () => {
  const ok = await api('POST', '/login', { body: { email: 'Ana@Example.com ', password: STRONG } });
  assert.equal(ok.status, 200);
  assert.ok(ok.body.token);
  const wrong = await api('POST', '/login', { body: { email: 'ana@example.com', password: 'Gresit#2026!!' } });
  const unknown = await api('POST', '/login', { body: { email: 'nimeni@example.com', password: STRONG } });
  assert.equal(wrong.status, 401);
  assert.equal(unknown.status, 401);
  assert.equal(wrong.body.message, unknown.body.message);
});

test('login: blocare dupa prea multe incercari gresite (429 + Retry-After)', async () => {
  await signup('carla@example.com');
  for (let i = 0; i < 2; i++) {
    const res = await api('POST', '/login', { body: { email: 'carla@example.com', password: 'Gresit#2026!!' } });
    assert.equal(res.status, 401);
  }
  const locked = await api('POST', '/login', { body: { email: 'carla@example.com', password: 'Gresit#2026!!' } });
  assert.equal(locked.status, 429);
  assert.ok(Number(locked.headers.get('retry-after')) > 0);
  // nici parola corecta nu merge cat timp e blocat
  const stillLocked = await api('POST', '/login', { body: { email: 'carla@example.com', password: STRONG } });
  assert.equal(stillLocked.status, 429);
});

test('token: lipsa, invalid, falsificat sau revocat la logout', async () => {
  assert.equal((await api('GET', '/posts')).status, 401);
  assert.equal((await api('GET', '/posts', { token: 'abc.def.ghi' })).status, 401);
  const { token } = await signup('dan@example.com');
  const [header, payload] = token.split('.');
  const forged = `${header}.${Buffer.from(JSON.stringify({ sub: 1, role: 'ADMIN', exp: 9999999999 })).toString('base64url')}.x`;
  assert.equal((await api('GET', '/posts', { token: forged })).status, 401);
  const none = `${Buffer.from('{"alg":"none"}').toString('base64url')}.${payload}.`;
  assert.equal((await api('GET', '/posts', { token: none })).status, 401);

  assert.equal((await api('GET', '/me', { token })).status, 200);
  assert.equal((await api('POST', '/logout', { token })).status, 204);
  assert.equal((await api('GET', '/me', { token })).status, 401);
});

test('postari: CRUD complet, cu autorul luat din token', async () => {
  const { token, user } = await signup('ema@example.com');
  const created = await api('POST', '/post', {
    token,
    body: { description: 'Prima calatorie', photo: '', user_id: '999', location: { latitude: 46.77, longitude: 23.59 } },
  });
  assert.equal(created.status, 201);
  assert.equal(created.body.user_id, String(user.id), 'user_id vine din token, nu din cerere');
  assert.equal(created.body.location, '46.77,23.59', 'locatia ca text, cum o citeste aplicatia');

  const list = await api('GET', '/posts', { token });
  assert.ok(list.body.some((p) => p.id === created.body.id));

  const read = await api('GET', `/post/${created.body.id}`, { token });
  assert.equal(read.body.description, 'Prima calatorie');

  const updated = await api('PUT', `/post/${created.body.id}`, { token, body: { description: 'Text nou' } });
  assert.equal(updated.status, 200);
  assert.equal(updated.body.description, 'Text nou');
  assert.equal(updated.body.location, '46.77,23.59');

  assert.equal((await api('DELETE', `/post/${created.body.id}`, { token })).status, 204);
  assert.equal((await api('GET', `/post/${created.body.id}`, { token })).status, 404);
});

test('permisiuni: doar autorul editeaza, autorul sau adminul sterge', async () => {
  const author = await signup('filip@example.com');
  const other = await signup('gina@example.com');
  const admin = await signup('admin@wanderlog.ro');
  assert.equal(admin.role, 'ADMIN');

  const post = (await api('POST', '/post', { token: author.token, body: { description: 'Postarea lui Filip' } })).body;
  assert.equal((await api('PUT', `/post/${post.id}`, { token: other.token, body: { description: 'x' } })).status, 403);
  assert.equal((await api('DELETE', `/post/${post.id}`, { token: other.token })).status, 403);
  assert.equal((await api('POST', '/post', { token: admin.token, body: { description: 'admin' } })).status, 403);
  assert.equal((await api('DELETE', `/post/${post.id}`, { token: admin.token })).status, 204);
});

test('Google / Apple simulat si vizitator anonim', async () => {
  const google = await api('POST', '/auth/provider', { body: { provider: 'GOOGLE', email: 'ion@gmail.com' } });
  assert.equal(google.status, 200);
  assert.equal(google.body.provider, 'GOOGLE');
  // acelasi cont la a doua conectare
  const again = await api('POST', '/auth/provider', { body: { provider: 'google', email: 'ION@gmail.com' } });
  assert.equal(again.body.user.id, google.body.user.id);
  // un cont cu parola nu poate fi preluat prin "Google"
  const takeover = await api('POST', '/auth/provider', { body: { provider: 'GOOGLE', email: 'ana@example.com' } });
  assert.equal(takeover.status, 409);
  // nici adresa de admin nu primeste rolul ADMIN prin Google
  const fakeAdmin = await api('POST', '/auth/provider', { body: { provider: 'APPLE', email: 'admin2@wanderlog.ro' } });
  assert.equal(fakeAdmin.body.role, 'USER');

  const guest = await api('POST', '/auth/anonymous', { body: { guestId: '1a2b3c4d' } });
  assert.equal(guest.status, 200);
  assert.equal(guest.body.provider, 'ANONYMOUS');
  assert.equal((await api('GET', '/posts', { token: guest.body.token })).status, 200, 'vizitatorul citeste');
  assert.equal(
    (await api('POST', '/post', { token: guest.body.token, body: { description: 'nu' } })).status,
    403,
    'vizitatorul nu publica',
  );
});

test('admin: lista de utilizatori, rol, blocare (tokenul celui blocat nu mai merge)', async () => {
  const admin = (await api('POST', '/login', { body: { email: 'admin@wanderlog.ro', password: STRONG } })).body;
  const user = await signup('horia@example.com');
  assert.equal((await api('GET', '/users', { token: user.token })).status, 403);

  const users = await api('GET', '/users', { token: admin.token });
  assert.equal(users.status, 200);
  assert.ok(users.body.every((u) => u.passwordHash === undefined), 'hash-urile nu ies de pe server');

  const promoted = await api('PATCH', `/users/${user.user.id}`, { token: admin.token, body: { role: 'ADMIN' } });
  assert.equal(promoted.body.role, 'ADMIN');
  await api('PATCH', `/users/${user.user.id}`, { token: admin.token, body: { role: 'USER' } });

  const blocked = await api('PATCH', `/users/${user.user.id}`, { token: admin.token, body: { blocked: true } });
  assert.equal(blocked.body.blocked, true);
  assert.equal((await api('GET', '/posts', { token: user.token })).status, 403);
  assert.equal(
    (await api('POST', '/login', { body: { email: 'horia@example.com', password: STRONG } })).status,
    403,
  );
  // ultimul admin nu se poate retrograda
  const self = await api('PATCH', `/users/${admin.user.id}`, { token: admin.token, body: { role: 'USER' } });
  assert.equal(self.status, 409);
});

test('poza prea mare e refuzata cu un mesaj clar', async () => {
  const { token } = await signup('lia@example.com');
  const res = await api('POST', '/post', { token, body: { description: 'mare', photo: 'A'.repeat(1_100_000) } });
  assert.equal(res.status, 413);
  assert.match(res.body.message, /Poza este prea mare/);
});

test('erori: ruta inexistenta, JSON invalid, corp prea mare', async () => {
  assert.equal((await api('GET', '/nu-exista')).status, 404);
  const res = await fetch(base + '/login', { method: 'POST', body: '{nu e json', headers: { 'Content-Type': 'application/json' } });
  assert.equal(res.status, 400);
  const big = await fetch(base + '/login', { method: 'POST', body: 'x'.repeat(13 * 1024 * 1024) }).catch(() => ({ status: 413 }));
  assert.equal(big.status, 413);
});

test('WebSocket: clientul autorizat primeste created / updated / deleted', async () => {
  const { token } = await signup('irina@example.com');
  const ws = new WebSocket(wsUrl);
  const events = [];
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = reject;
  });
  ws.onmessage = (m) => events.push(JSON.parse(m.data));
  ws.send(JSON.stringify({ type: 'authorization', payload: { token } }));
  await new Promise((r) => setTimeout(r, 100));

  const post = (await api('POST', '/post', { token, body: { description: 'Live', location: '45.1,25.2' } })).body;
  await api('PUT', `/post/${post.id}`, { token, body: { description: 'Live 2' } });
  await api('DELETE', `/post/${post.id}`, { token });
  await new Promise((r) => setTimeout(r, 200));
  ws.close();

  assert.deepEqual(events.map((e) => e.event), ['created', 'updated', 'deleted']);
  assert.equal(events[0].payload.item.id, post.id);
  assert.equal(events[1].payload.item.description, 'Live 2');
});

test('WebSocket: fara autorizare nu primeste nimic; token invalid inchide conexiunea', async () => {
  const { token } = await signup('jean@example.com');
  const silent = new WebSocket(wsUrl);
  const received = [];
  await new Promise((r) => (silent.onopen = r));
  silent.onmessage = (m) => received.push(m.data);
  await api('POST', '/post', { token, body: { description: 'Secret' } });
  await new Promise((r) => setTimeout(r, 150));
  assert.equal(received.length, 0);
  silent.close();

  const bad = new WebSocket(wsUrl);
  await new Promise((r) => (bad.onopen = r));
  const closed = new Promise((r) => (bad.onclose = (e) => r(e.code)));
  bad.send(JSON.stringify({ type: 'authorization', payload: { token: 'invalid' } }));
  assert.equal(await closed, 4001);
});

test('datele raman dupa repornirea serverului', async () => {
  const { token } = await signup('karla@example.com');
  const post = (await api('POST', '/post', { token, body: { description: 'Persistenta' } })).body;
  // o noua instanta citeste aceeasi baza de date (si acelasi secret pentru token-uri)
  const second = createApp({ dataDir, quiet: true });
  assert.ok(second.store.findPost(post.id));
  assert.ok(second.store.findUserByEmail('karla@example.com'));
  assert.equal(second.store.secret, app.store.secret);
  await second.close();
});
