'use strict';

// Poze ca fisiere, aprecieri, comentarii si schimbarea parolei.

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../src/app');

const STRONG = 'Calatorie#2026!';
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), crypto.randomBytes(2000), Buffer.from([0xff, 0xd9])]);
let app;
let base;
let dir;

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wl-social-'));
  app = createApp({ dataDir: dir, quiet: true });
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${app.server.address().port}`;
});

after(async () => {
  await app.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

async function api(method, route, { token, body, raw, type } = {}) {
  const res = await fetch(base + route, {
    method,
    headers: {
      ...(raw ? { 'Content-Type': type || 'image/jpeg' } : body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: raw ?? (body ? JSON.stringify(body) : undefined),
  });
  const contentType = res.headers.get('content-type') || '';
  if (contentType.startsWith('image/')) return { status: res.status, bytes: Buffer.from(await res.arrayBuffer()), type: contentType };
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function signup(email) {
  const res = await api('POST', '/signup', { body: { email, password: STRONG } });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  return { token: res.body.token, id: String(res.body.user.id) };
}

const uuid = () => crypto.randomUUID();
let ana;
let dan;
let admin;
let post;

test('poza se urca separat; doar continut de imagine; reincercarea nu o dubleaza', async () => {
  ana = await signup('ana@example.com');
  dan = await signup('dan@example.com');
  admin = await signup('admin@wanderlog.ro');
  const id = uuid();
  const notImage = await api('PUT', `/photos/${id}`, { token: ana.token, raw: Buffer.from('<script>alert(1)</script>'), type: 'image/jpeg' });
  assert.equal(notImage.status, 415);
  const first = await api('PUT', `/photos/${id}`, { token: ana.token, raw: JPEG });
  assert.equal(first.status, 201);
  assert.equal(first.body.url, `/photos/${id}`);
  const again = await api('PUT', `/photos/${id}`, { token: ana.token, raw: JPEG });
  assert.equal(again.status, 200, 'aceeasi poza retrimisa');
  assert.equal((await api('PUT', `/photos/${id}`, { token: dan.token, raw: JPEG })).status, 409, 'id-ul altcuiva');
  assert.equal((await api('PUT', `/photos/${uuid()}`, { token: admin.token, raw: JPEG })).status, 403, 'adminul nu publica');
  const big = Buffer.concat([JPEG, Buffer.alloc(4 * 1024 * 1024)]);
  assert.equal((await api('PUT', `/photos/${uuid()}`, { token: ana.token, raw: big })).status, 413);

  // postare cu adresa pozei; poza altcuiva nu poate fi folosita
  const stolen = await api('PUT', `/post/${uuid()}`, { token: dan.token, body: { title: 'Furat', photo_url: `/photos/${id}` } });
  assert.equal(stolen.status, 400);
  const created = await api('PUT', `/post/${uuid()}`, { token: ana.token, body: { title: 'Cheile Turzii', photo_url: `/photos/${id}`, status: 'DRAFT' } });
  assert.equal(created.status, 201);
  post = created.body;
  assert.equal(post.photo_url, `/photos/${id}`);
});

test('poza unei ciorne o vede doar autorul; dupa publicare o vad toti cei autentificati', async () => {
  const id = post.photo_url.split('/').pop();
  const own = await api('GET', `/photos/${id}`, { token: ana.token });
  assert.equal(own.status, 200);
  assert.ok(own.bytes.equals(JPEG));
  assert.equal(own.type, 'image/jpeg');
  assert.equal((await api('GET', `/photos/${id}`, { token: dan.token })).status, 404);
  assert.equal((await api('GET', `/photos/${id}`)).status, 401);
  const published = await api('PUT', `/post/${post.id}`, { token: ana.token, body: { ...post, status: 'ACTIVE', updated_at: new Date().toISOString() } });
  assert.equal(published.status, 200);
  assert.equal((await api('GET', `/photos/${id}`, { token: dan.token })).status, 200);
});

test('aprecieri: numarul si "apreciat de mine" sunt per utilizator; idempotente', async () => {
  assert.equal((await api('PUT', `/post/${post.id}/like`, { token: dan.token })).body.like_count, 1);
  const again = await api('PUT', `/post/${post.id}/like`, { token: dan.token });
  assert.equal(again.body.like_count, 1, 'a doua apreciere nu se numara');
  assert.equal(again.body.liked_by_me, true);
  const asAna = (await api('GET', `/post/${post.id}`, { token: ana.token })).body;
  assert.equal(asAna.like_count, 1);
  assert.equal(asAna.liked_by_me, false);
  assert.equal((await api('PUT', `/post/${post.id}/like`, { token: admin.token })).status, 403);
  const guest = await api('POST', '/auth/anonymous', { body: { guestId: 'guest-1234' } });
  assert.equal((await api('PUT', `/post/${post.id}/like`, { token: guest.body.token })).status, 403);
  const unliked = await api('DELETE', `/post/${post.id}/like`, { token: dan.token });
  assert.equal(unliked.body.like_count, 0);
  assert.equal(unliked.body.liked_by_me, false);
});

test('comentarii: adaugare, validare, stergere de catre autor / autorul postarii / admin', async () => {
  assert.equal((await api('POST', `/post/${post.id}/comments`, { token: dan.token, body: { text: '   ' } })).status, 400);
  assert.equal((await api('POST', `/post/${post.id}/comments`, { token: dan.token, body: { text: 'x'.repeat(501) } })).status, 400);
  const c1 = await api('POST', `/post/${post.id}/comments`, { token: dan.token, body: { text: 'Superb loc!' } });
  assert.equal(c1.status, 201);
  assert.equal(c1.body.author, 'dan');
  const c2 = (await api('POST', `/post/${post.id}/comments`, { token: dan.token, body: { text: 'Merg și eu' } })).body;
  const c3 = (await api('POST', `/post/${post.id}/comments`, { token: ana.token, body: { text: 'Mulțumesc!' } })).body;

  const list = await api('GET', `/post/${post.id}/comments`, { token: ana.token });
  assert.deepEqual(list.body.map((c) => c.text), ['Superb loc!', 'Merg și eu', 'Mulțumesc!']);
  assert.ok(list.body.every((c) => c.can_delete), 'autoarea postarii le poate sterge pe toate');
  assert.equal((await api('GET', `/post/${post.id}`, { token: dan.token })).body.comment_count, 3);

  const third = await signup('ion@example.com');
  assert.equal((await api('DELETE', `/comments/${c1.body.id}`, { token: third.token })).status, 403);
  assert.equal((await api('DELETE', `/comments/${c1.body.id}`, { token: dan.token })).status, 204, 'autorul comentariului');
  assert.equal((await api('DELETE', `/comments/${c2.id}`, { token: ana.token })).status, 204, 'autoarea postarii');
  assert.equal((await api('DELETE', `/comments/${c3.id}`, { token: admin.token })).status, 204, 'adminul');
  assert.equal((await api('GET', `/post/${post.id}`, { token: dan.token })).body.comment_count, 0);
});

test('stergerea postarii sterge si aprecierile si comentariile ei', async () => {
  await api('PUT', `/post/${post.id}/like`, { token: dan.token });
  await api('POST', `/post/${post.id}/comments`, { token: dan.token, body: { text: 'Ultimul' } });
  assert.equal((await api('DELETE', `/post/${post.id}`, { token: ana.token })).status, 204);
  assert.equal(app.store.data.likes.filter((l) => l.post_id === post.id).length, 0);
  assert.equal(app.store.data.comments.filter((c) => c.post_id === post.id).length, 0);
});

test('schimbarea parolei: verifica parola actuala si regulile de securitate', async () => {
  const wrong = await api('POST', '/me/password', { token: ana.token, body: { currentPassword: 'gresit', newPassword: 'Munte#Albastru2027' } });
  assert.equal(wrong.status, 400);
  const weak = await api('POST', '/me/password', { token: ana.token, body: { currentPassword: STRONG, newPassword: 'parola' } });
  assert.equal(weak.status, 400);
  const ok = await api('POST', '/me/password', { token: ana.token, body: { currentPassword: STRONG, newPassword: 'Munte#Albastru2027' } });
  assert.equal(ok.status, 200);
  assert.equal((await api('POST', '/login', { body: { email: 'ana@example.com', password: STRONG } })).status, 401);
  assert.equal((await api('POST', '/login', { body: { email: 'ana@example.com', password: 'Munte#Albastru2027' } })).status, 200);
});
