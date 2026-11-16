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
| GET | `/health` | – | Verificare că serverul rulează |

**WebSocket** (aceeași adresă, `ws://…`): clientul trimite
`{"type":"authorization","payload":{"token":"…"}}`, iar serverul trimite clienților autorizați
`{"event":"created|updated|deleted","payload":{"item":{…}}}` la fiecare modificare.

Erorile au forma `{"message": "…"}`, cu mesajul în română, pe înțelesul utilizatorului.

## Câmpurile unei postări

`id`, `title` (≤120), `description` (≤2000), `photo` (Base64, ≤1.000.000 caractere, altfel 413), `rating` (0–5),
`visit_date` (ms), `status` (`ACTIVE` / `DRAFT`), `hidden`, `location`, `user_id`, `created_at`, `updated_at`.
WebSocket-ul trimite fiecărui client doar ce are voie să vadă (ciorna altcuiva ajunge ca `deleted`).

## Securitate

- Parolele se salvează doar ca hash **scrypt** cu salt. Există un hash fals pentru conturile inexistente, ca durata răspunsului să nu dezvăluie ce adrese au cont.
- Token-ul e **JWT HS256**, valabil 7 zile, revocabil la logout. Semnătura se compară în timp constant, iar tokenurile `alg: none` sunt refuzate.
- Permisiunile se verifică **pe server**, din token, nu din ce trimite aplicația.
- Google / Apple sunt simulate. Ca să nu poată fi preluat un cont existent, o adresă care are deja cont cu parolă (sau cu alt furnizor) e refuzată, iar conturile create așa nu primesc niciodată rolul ADMIN.
- Rolul ADMIN îl primește la înregistrare adresa `admin@wanderlog.ro` (variabila de mediu `ADMIN_EMAILS`).
