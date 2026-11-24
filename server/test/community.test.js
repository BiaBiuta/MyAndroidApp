'use strict';

// Comentarii "ca in viata reala" (raspunsuri, editare, aprecieri), calatorii, profiluri, urmarire, galerie.

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createApp } = require('../src/app');

const STRONG = 'Calatorie#2026!';
const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), crypto.randomBytes(500), Buffer.from([0xff, 0xd9])]);
let app;
let base;
let dir;

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wl-community-'));
  app = createApp({ dataDir: dir, quiet: true });
  await new Promise((resolve) => app.server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${app.server.address().port}`;
});

after(async () => {
  await app.close();
  fs.rmSync(dir, { recursive: true, force: true });
});

async function api(method, route, { token, body, raw } = {}) {
  const res = await fetch(base + route, {
    method,
    headers: {
      ...(raw ? { 'Content-Type': 'image/jpeg' } : body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: raw ?? (body ? JSON.stringify(body) : undefined),
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function signup(email) {
  const res = await api('POST', '/signup', { body: { email, password: STRONG } });
  assert.equal(res.status, 201);
  return { token: res.body.token, id: String(res.body.user.id) };
}

const uuid = () => crypto.randomUUID();
let ana;
let dan;
let admin;
let trip;
let post;

test('calatorie: creata cu id de pe telefon, postarile o refera, doar autorul o poate folosi', async () => {
  ana = await signup('ana@example.com');
  dan = await signup('dan@example.com');
  admin = await signup('admin@wanderlog.ro');
  const id = uuid();
  assert.equal((await api('PUT', `/trips/${id}`, { token: ana.token, body: { title: '' } })).status, 400);
  assert.equal((await api('PUT', `/trips/${id}`, { token: ana.token, body: { title: 'X', start_date: 10, end_date: 5 } })).status, 400);
  const created = await api('PUT', `/trips/${id}`, { token: ana.token, body: { title: 'Transilvania', start_date: 1000, end_date: 5000 } });
  assert.equal(created.status, 201);
  trip = created.body;
  assert.equal((await api('PUT', `/trips/${id}`, { token: dan.token, body: { title: 'Furt' } })).status, 404);
  assert.equal((await api('PUT', `/post/${uuid()}`, { token: dan.token, body: { title: 'Nu', trip_id: id } })).status, 400);

  // galerie: pozele trebuie urcate de autor; maxim 5 in plus
  const photos = [];
  for (let i = 0; i < 3; i++) {
    const pid = uuid();
    assert.equal((await api('PUT', `/photos/${pid}`, { token: ana.token, raw: JPEG })).status, 201);
    photos.push(`/photos/${pid}`);
  }
  const tooMany = await api('PUT', `/post/${uuid()}`, { token: ana.token, body: { title: 'x', gallery: Array(6).fill(photos[1]) } });
  assert.equal(tooMany.status, 400);
  const res = await api('PUT', `/post/${uuid()}`, {
    token: ana.token,
    body: { title: 'Cheile Turzii', place: 'Cheile Turzii, Cluj', rating: 5, photo_url: photos[0], gallery: photos.slice(1), trip_id: id },
  });
  assert.equal(res.status, 201, JSON.stringify(res.body));
  post = res.body;
  assert.deepEqual(post.gallery, photos.slice(1));
  assert.equal(post.trip_id, id);
  // pozele din galerie le vad si ceilalti
  assert.equal((await fetch(base + photos[2], { headers: { Authorization: `Bearer ${dan.token}` } })).status, 200);

  const trips = await api('GET', `/users/${ana.id}/trips`, { token: dan.token });
  assert.equal(trips.body.length, 1);
  assert.equal(trips.body[0].post_count, 1);
  assert.equal(trips.body[0].cover, photos[0]);
});

test('comentarii: raspunsuri pe un nivel, editare marcata, aprecieri, stergerea ia si raspunsurile', async () => {
  const top = (await api('POST', `/post/${post.id}/comments`, { token: dan.token, body: { text: 'Superb!' } })).body;
  const reply = (await api('POST', `/post/${post.id}/comments`, { token: ana.token, body: { text: 'Mulțumesc!', parent_id: top.id } })).body;
  assert.equal(reply.parent_id, top.id);
  const nested = (await api('POST', `/post/${post.id}/comments`, { token: dan.token, body: { text: 'Cu drag', parent_id: reply.id } })).body;
  assert.equal(nested.parent_id, top.id, 'raspunsul la un raspuns ramane in acelasi fir');

  assert.equal((await api('PATCH', `/comments/${top.id}`, { token: ana.token, body: { text: 'hack' } })).status, 403);
  const edited = await api('PATCH', `/comments/${top.id}`, { token: dan.token, body: { text: 'Superb loc!' } });
  assert.equal(edited.status, 200);
  assert.ok(edited.body.edited_at);
  assert.equal(edited.body.text, 'Superb loc!');

  assert.equal((await api('PUT', `/comments/${top.id}/like`, { token: ana.token })).body.like_count, 1);
  assert.equal((await api('PUT', `/comments/${top.id}/like`, { token: ana.token })).body.like_count, 1, 'idempotent');
  const asDan = (await api('GET', `/post/${post.id}/comments`, { token: dan.token })).body.find((c) => c.id === top.id);
  assert.equal(asDan.like_count, 1);
  assert.equal(asDan.liked_by_me, false);
  assert.equal(asDan.can_edit, true);
  assert.equal((await api('PUT', `/comments/${top.id}/like`, { token: admin.token })).status, 403);

  assert.equal((await api('GET', `/post/${post.id}`, { token: dan.token })).body.comment_count, 3);
  assert.equal((await api('DELETE', `/comments/${top.id}`, { token: dan.token })).status, 204);
  assert.equal((await api('GET', `/post/${post.id}/comments`, { token: dan.token })).body.length, 0, 'raspunsurile au plecat odata cu el');
  assert.equal(app.store.data.commentLikes.length, 0);
});

test('profil public: fara e-mail, cu statistici; urmarire', async () => {
  const profile = await api('GET', `/users/${ana.id}/profile`, { token: dan.token });
  assert.equal(profile.status, 200);
  assert.equal(profile.body.name, 'ana');
  assert.equal(profile.body.email, undefined, 'adresa de e-mail nu e publica');
  assert.equal(profile.body.post_count, 1);
  assert.equal(profile.body.place_count, 1);
  assert.equal(profile.body.average_rating, 5);
  assert.equal(profile.body.trip_count, 1);
  assert.equal((await api('GET', `/users/9999/profile`, { token: dan.token })).status, 404);
  assert.equal((await api('PUT', `/users/${dan.id}/follow`, { token: dan.token })).status, 400, 'nu se urmareste singur');
  const followed = await api('PUT', `/users/${ana.id}/follow`, { token: dan.token });
  assert.equal(followed.body.followers, 1);
  assert.equal(followed.body.followed_by_me, true);
  assert.deepEqual((await api('GET', '/me/following', { token: dan.token })).body, [ana.id]);
  const unfollowed = await api('DELETE', `/users/${ana.id}/follow`, { token: dan.token });
  assert.equal(unfollowed.body.followers, 0);
});

test('stergerea calatoriei pastreaza postarile, dar le scoate din calatorie', async () => {
  assert.equal((await api('DELETE', `/trips/${trip.id}`, { token: dan.token })).status, 404);
  assert.equal((await api('DELETE', `/trips/${trip.id}`, { token: ana.token })).status, 204);
  const after = await api('GET', `/post/${post.id}`, { token: ana.token });
  assert.equal(after.body.trip_id, '');
  assert.equal((await api('GET', `/users/${ana.id}/trips`, { token: ana.token })).body.length, 0);
  assert.equal((await api('PUT', `/trips/${trip.id}`, { token: ana.token, body: { title: 'Din nou' } })).status, 410);
});
