# Serverul WanderLog

Server REST + WebSocket pentru aplicația Android. Folosește **doar Node.js**, fără pachete externe,
deci nu are nevoie de `npm install`.

## Pornire

```bash
cd server
node server.js          # sau: npm start
```

Cerințe: Node.js 22 sau mai nou. Serverul ascultă pe portul 3000 și afișează adresele la care îl găsește aplicația:

- **emulator Android:** `http://10.0.2.2:3000` (adresa implicită din aplicație, `SERVER_HOST` în `app/build.gradle.kts`);
- **telefon real în același Wi-Fi:** IP-ul calculatorului, de exemplu `192.168.1.7:3000`. Îl pui în `SERVER_HOST` și în `app/src/main/res/xml/network_security_config.xml`.

Datele se păstrează în `server/data/db.json`, iar secretul cu care se semnează token-urile în `server/data/jwt-secret.key`. Ambele se creează la prima pornire și nu se urcă pe GitHub.

Teste: `npm test` rulează 13 teste end-to-end (autentificare, token, CRUD, permisiuni, WebSocket, persistență).

## Endpoint-uri

| Metodă | Rută | Token | Ce face |
|---|---|---|---|
| POST | `/signup` | – | Creează un cont (e-mail + parolă puternică), întoarce token-ul |
| POST | `/login` | – | Autentificare; blocare temporară după 5 parole greșite (429) |
| POST | `/auth/provider` | – | Google / Apple **simulat**: `{provider, email}` |
| POST | `/auth/anonymous` | – | Vizitator anonim: `{guestId}` (doar citire) |
| GET | `/me` | ✓ | Contul din token |
| POST | `/logout` | ✓ | Revocă token-ul curent |
| GET | `/posts` | ✓ | Postările vizibile: ciornele doar autorului, cele ascunse doar autorului și adminilor |
| GET | `/post/:id` | ✓ | O postare (404 dacă nu ai voie s-o vezi) |
| POST | `/post` | ✓ | Postare nouă (doar USER; id generat de client, idempotent; autorul vine din token) |
| PUT | `/post/:id` | ✓ | Upsert (creare sau editare, doar autorul). Conflict: dacă serverul are o versiune mai nouă (`updated_at`) → **409** `{message, item}` (last-write-wins); postare ștearsă → **410** |
| DELETE | `/post/:id` | ✓ | Ștergere (autorul sau ADMIN); lasă un „tombstone” ca un client offline să afle de ștergere |
| PATCH | `/post/:id/visibility` | ✓ ADMIN | `{hidden: true/false}` — ascunde / reafișează o postare |
| GET | `/stats` | ✓ ADMIN | Statistici: utilizatori (total/activi/blocați/admini), postări (active/ciorne/ascunse/șterse de moderatori) |
| GET | `/users` | ✓ ADMIN | Lista de utilizatori (fără hash-uri) |
| PATCH | `/users/:id` | ✓ ADMIN | `{role}` sau `{blocked}` |
| GET | `/health` | – | Verificare că serverul rulează; `version` (3) și `features` arată că e versiunea nouă |

**WebSocket** (aceeași adresă, `ws://…`): clientul trimite
`{"type":"authorization","payload":{"token":"…"}}`, iar serverul trimite clienților autorizați
`{"event":"created|updated|deleted","payload":{"item":{…}}}` la fiecare modificare.

Erorile au forma `{"message": "…"}`, cu mesajul în română, pe înțelesul utilizatorului.

## Poze, aprecieri, comentarii, profil

| Metodă | Cale | Autentificare | Descriere |
|---|---|---|---|
| PUT | `/photos/:id` | USER | Urcă poza unei postări (corpul = JPEG / PNG / WebP, max. 3 MB; conținutul e verificat). `id` = UUID generat pe telefon, deci reîncercarea nu dublează poza. Răspuns: `{id, url: "/photos/<id>"}` |
| GET | `/photos/:id` | ✓ | Poza (doar autorul ei sau cine poate vedea o postare care o folosește; poza unei ciorne nu e publică) |
| PUT / DELETE | `/post/:id/like` | USER | Apreciază / retrage aprecierea (idempotent) |
| GET | `/post/:id/comments` | ✓ | Comentariile, cu `parent_id`, `edited_at`, `like_count`, `liked_by_me`, `can_edit`, `can_delete` pentru utilizatorul curent |
| POST | `/post/:id/comments` | USER | Comentariu nou `{text, parent_id?}` (1–500 caractere); răspunsul la un răspuns se atașează firului principal (un singur nivel) |
| PATCH | `/comments/:id` | autorul | Editează textul `{text}`; setează `edited_at` |
| PUT / DELETE | `/comments/:id/like` | USER | Apreciază / retrage aprecierea unui comentariu |
| DELETE | `/comments/:id` | ✓ | Autorul comentariului, autorul postării sau un admin (se șterg și răspunsurile) |
| POST | `/me/password` | ✓ (cont cu parolă) | `{currentPassword, newPassword}`; parola nouă respectă aceleași reguli ca la înregistrare |

Orice schimbare la comentarii trimite pe WebSocket `{"event":"comments","payload":{"item":{"id":"<postId>"}}}` celor care văd postarea (lista se reîncarcă în timp real).

## Călătorii, profiluri, urmărire

| Metodă | Cale | Autentificare | Descriere |
|---|---|---|---|
| PUT | `/trips/:id` | USER (proprietarul) | Creează / actualizează o călătorie `{title (≤80), description (≤1000), start_date, end_date}`; `id` = UUID de pe telefon (merge offline + sincronizare). 410 dacă a fost ștearsă |
| DELETE | `/trips/:id` | proprietarul | Șterge călătoria; postările rămân, cu `trip_id` gol |
| GET | `/users/:id/trips` | ✓ | Călătoriile unui utilizator, cu `post_count` și `cover` |
| GET | `/users/:id/profile` | ✓ | Profil public (fără e-mail): `name`, `post_count`, `place_count`, `average_rating`, `likes_received`, `trip_count`, `followers`, `following`, `followed_by_me`, `is_me` |
| PUT / DELETE | `/users/:id/follow` | USER | Urmărește / nu mai urmări (nu te poți urmări pe tine); răspunde cu profilul actualizat |
| GET | `/me/following` | ✓ | Id-urile celor urmăriți (filtrul „Urmăriți” din aplicație) |

Fiecare postare primită de un utilizator are și `like_count`, `liked_by_me` (calculat pentru el) și `comment_count`; WebSocket-ul trimite fiecăruia varianta lui. Postarea stocată are doar `photo_url` (adresa pozei); poza în sine e un fișier în `data/photos/`.

## Câmpurile unei postări

`id`, `title` (≤120), `photo_url` (`/photos/<id>`, poza principală urcată de autor), `gallery` (max. 5 poze suplimentare `/photos/<id>`, toate ale autorului), `trip_id` (o călătorie proprie, sau gol), `place` (numele locului, ≤160), `description` (≤2000), `photo` (Base64, ≤1.000.000 caractere, altfel 413), `rating` (0–5),
`visit_date` (ms), `status` (`ACTIVE` / `DRAFT`), `hidden`, `location`, `user_id`, `created_at`, `updated_at`.
WebSocket-ul trimite fiecărui client doar ce are voie să vadă (ciorna altcuiva ajunge ca `deleted`).

## Securitate

- Parolele se salvează doar ca hash **scrypt** cu salt. Există un hash fals pentru conturile inexistente, ca durata răspunsului să nu dezvăluie ce adrese au cont.
- Token-ul e **JWT HS256**, valabil 7 zile, revocabil la logout. Semnătura se compară în timp constant, iar tokenurile `alg: none` sunt refuzate.
- Permisiunile se verifică **pe server**, din token, nu din ce trimite aplicația.
- Google / Apple sunt simulate. Ca să nu poată fi preluat un cont existent, o adresă care are deja cont cu parolă (sau cu alt furnizor) e refuzată, iar conturile create așa nu primesc niciodată rolul ADMIN.
- Rolul ADMIN îl primește la înregistrare adresa `admin@wanderlog.ro` (variabila de mediu `ADMIN_EMAILS`).

## Trackerul WanderLog (IoT)

Un tracker GPS de călătorie **simulat** (`device/tracker.js`) care se asociază cu aplicația și trimite la server locurile prin care trece.

### Pornire (3 terminale)

```
npm start                     # serverul (portul 3000)
node device/tracker.js        # trackerul (portul 4000; din emulator: 10.0.2.2:4000)
```

În consola trackerului: `p` = modul de asociere (2 minute), `s` = stare, `r` = resetare (cheie nouă), `q` = ieșire.
În aplicație: **Trackere → Asociază un tracker**, adresa `10.0.2.2:4000`, apoi codul de 6 cifre afișat de tracker.

### Securitate

| Cerință | Implementare |
|---|---|
| Asociere securizată cu cod out-of-band | Asocierea merge doar în modul de asociere (ca butonul fizic al unui dispozitiv). Schimb de chei ECDH P-256 + **angajament** (commitment) la nonce-ul trackerului; codul de 6 cifre e derivat din transcriptul întregului schimb și **afișat doar pe tracker**. Utilizatorul îl tastează în aplicație: dacă cineva s-a interpus (MITM), codurile diferă. Cheia de asociere K = HKDF-SHA256(secret ECDH, transcript); ambele părți dovedesc că o au (HMAC de confirmare). Max. 3 încercări, sesiune de 2 minute, un singur proprietar. |
| Chei per dispozitiv + revocare | Fiecare tracker are cheia lui ECDSA P-256 (id = amprenta cheii). Aplicația o primește autentificat cu K și o înregistrează pe server (`POST /devices`). Trackerul **semnează** fiecare cerere (`X-WL-Device`, `X-WL-Time`, `X-WL-Nonce`, `X-WL-Signature` peste metodă, cale, oră, nonce și hash-ul corpului); serverul respinge semnături greșite, cereri vechi și reluări. `POST /devices/:id/revoke` (din aplicație) = trackerul pierdut primește 403 la orice cerere și se oprește; cheia lui nu mai poate fi înregistrată. |
| Verificarea actualizărilor | Actualizările (`updates/*.json`) sunt semnate ECDSA de **producător** cu `tools/vendor-private.pem` (secretă, în `.gitignore`). Aplicația are doar cheia publică și **refuză să trimită** o actualizare cu semnătură invalidă, pentru alt model sau mai veche (rollback). Trackerul verifică și el (apărare în adâncime). Serverul doar distribuie fișierele și nu poate crea o actualizare acceptată. |

Comenzile aplicației către tracker (stare, actualizare, dezasociere) sunt autentificate cu HMAC-ul cheii K (+ oră și nonce).

### Endpoint-uri

| Metodă | Cale | Autentificare | Descriere |
|---|---|---|---|
| POST | `/devices` | JWT (USER) | Înregistrează trackerul asociat: `{id, name, model, firmware, publicKey}` |
| GET | `/devices` | JWT | Trackerele contului |
| POST | `/devices/:id/revoke` | JWT (proprietar) | Revocare definitivă |
| POST | `/devices/:id/firmware` | JWT (proprietar) | Versiunea instalată |
| GET | `/devices/:id/visits` | JWT (proprietar) | Locurile trimise de tracker |
| GET | `/device-updates` | JWT | Actualizările publicate (aplicația le verifică) |
| POST | `/device/visits` | semnătura trackerului | Un loc vizitat |
| GET | `/device/status` | semnătura trackerului | Starea (403 dacă e revocat) |

### Actualizări de test

| Fișier | Rezultat așteptat |
|---|---|
| `wlt1-1.1.0.json` | semnătură validă → se instalează |
| `wlt1-1.2.0-modificat.json` | conținut modificat după semnare → **refuzată** |
| `wlt1-0.9.0-veche.json` | semnătură validă, dar versiune mai veche → **refuzată** (rollback) |

Actualizări noi: `node tools/sign-update.js sign --version 1.3.0 --interval 30 --notes "..."`; o copie falsificată: `node tools/sign-update.js tamper updates/wlt1-1.3.0.json`.

