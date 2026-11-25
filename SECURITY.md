# WanderLog – App Hardening

Două măsuri din lista „Bonus Security Feature Ideas → App Hardening”: **Secure logging** și **Secrets hygiene**. Pentru fiecare: atacul, apărarea, unde e în cod și cum se demonstrează.

---

## 1. Secure logging

### Atacul
Logcat-ul se poate citi cu `adb logcat` de pe orice PC la care e conectat telefonul. Apare și în rapoartele de eroare (bug reports), iar pe telefoanele vechi sau cu root îl pot citi alte aplicații. Înainte, WanderLog scria acolo:

| Ce | Unde |
|---|---|
| coordonatele GPS ale utilizatorului | `maps/LocationMonitor.kt`, `maps/MyLocation.kt`, `maps/MyLocationViewModel.kt` |
| postări întregi (titlu, text, loc, coordonate) primite pe WebSocket | `todo/data/remote/PostWsClient.kt`, `todo/data/PostRepository.kt` |
| e-mailurile utilizatorilor, la administrare | `auth/data/RoleRepository.kt` |

### Apărarea
- **`core/logging/LogRedactor.kt`** curăță fiecare mesaj înainte să ajungă în logcat. Ascunde tokenurile JWT și `Bearer …`, tokenurile locale `local-…`, parolele și hash-urile, cheile de asociere, conținutul postărilor (titlu, text, loc, poze), coordonatele și pozele Base64. Din e-mail rămân doar prima literă și domeniul: `b***@gmail.com`.
- **`core/logging/SafeLog.kt`** e singurul loc din aplicație care scrie în logcat. Toate cele 109 apeluri `Log.d` / `Log.w` din 33 de fișiere au fost înlocuite cu `SafeLog`.
  - În **release**, mesajele de depanare (`d`, `i`) nu se mai scriu deloc. La erori apare doar tipul excepției, nu și mesajul ei.
  - Tag-urile au prefixul `WL/`, ca logurile aplicației să se filtreze ușor.
- **`app/proguard-rules.pro`** (`-assumenosideeffects`): în release, R8 scoate complet apelurile de depanare din cod, cu tot cu textul lor.
- Cele două loguri care scriau postări întregi (`PostWsClient`, `PostRepository`) scriu acum doar tipul evenimentului, id-ul și dimensiunea.

### Dovada
- Testul unitar `app/src/test/.../core/logging/LogRedactorTest.kt` (8 teste) folosește exact mesajele de mai sus: JWT, parole, e-mail, GPS, WebSocket, Base64.
- **Demo** (build de debug, telefonul conectat prin cablu): Android Studio → fereastra **Logcat** → filtrul `tag:WL/Demo`. În aplicație: **Profil → cardul „Demo securitate: loguri”** (există doar în debug).
  1. **Atacul:** comutatorul „Ascunde datele sensibile” **oprit** → „Scrie în log date de test”. În Logcat apar e-mailul, tokenul de sesiune, coordonatele, textul unei postări și parolele.
  2. **Apărarea:** comutatorul **pornit** → același buton. Apar `b***@gmail.com`, `Bearer [token ascuns]`, `[locație ascunsă]`, `"title":[ascuns]`, `"newPassword":[ascuns]`.
  3. În release cardul nu există, iar curățarea nu poate fi oprită (`SafeLog.redactionEnabled`).
---

## 2. Secrets hygiene

### Atacul
Un APK se decompilează în câteva secunde, de exemplu cu **Build → Analyze APK** din Android Studio sau cu `jadx`. Orice cheie API, parolă sau cheie privată pusă în cod sau în resurse poate fi recuperată. La fel, ce a ajuns o dată pe GitHub rămâne în **istoricul** Git, chiar dacă fișierul a fost șters ulterior.

### Ce secrete are proiectul și unde stau

| Secret | Unde stă | E în APK? | E pe GitHub? |
|---|---|---|---|
| Cheia de semnare a tokenurilor (HMAC) | `server/data/jwt-secret.key`, generată la prima pornire | nu | nu (`server/.gitignore`: `data/`) |
| Cheia **privată** a producătorului trackerului | `server/tools/vendor-private.pem` | nu | nu (`server/.gitignore`) |
| Parolele utilizatorilor | doar hash: scrypt pe server, PBKDF2 pe telefon | nu | nu |
| Tokenul de sesiune pe telefon | DataStore, criptat cu Android Keystore (`core/security/SecureStore.kt`) | nu | nu |
| Cheia de semnare a aplicației (release) | `keystore.properties` + `*.jks`, în afara codului | nu | nu (`.gitignore`) |

Ce **apare** în APK și **nu** e secret:
- **cheia publică** a producătorului trackerului (`iot/update/UpdateVerifier.kt`): e publică prin definiție, servește doar la *verificarea* semnăturilor;
- adresa serverului (`BuildConfig.SERVER_HOST`);
- stilul hărții OpenFreeMap, care nu cere cheie API.

### Ce s-a schimbat
- **R8 activat** pentru release (`isMinifyEnabled = true`, `isShrinkResources = true`). Codul e ofuscat; numele se păstrează doar pentru clasele care devin JSON (`app/proguard-rules.pro`).
- **Semnarea release-ului** se citește din `keystore.properties`, care nu e în repo. Fără el, release-ul se semnează cu cheia de debug, ca să poată fi instalat pentru demo.
- `.gitignore`: `keystore.properties`, `*.jks`, `*.keystore`.

### Problemă găsită în istoricul Git și remedierea ei
O cheie **Google Maps API** (`AIzaSy…`) a fost în `AndroidManifest.xml` din decembrie 2024 până pe 16 noiembrie 2026, când harta a trecut la MapLibre + OpenFreeMap. Codul actual nu o mai conține, dar ea rămâne în istoric.

Remedierea corectă e **revocarea cheii**, nu ascunderea ei: Google Cloud Console → APIs & Services → Credentials → cheia respectivă → **Delete** (sau Regenerate). După revocare, cheia din istoric nu mai valorează nimic. Rescrierea istoricului (`git filter-repo` + push forțat) nu ajută: cheia a fost deja publică, iar copiile existente o păstrează.

### Demo
1. **Build → Build Bundle(s) / APK(s) → Build APK(s)** pe varianta *release* (Build Variants → `release`). Fișierul apare în `app/build/outputs/apk/release/`.
2. **Build → Analyze APK…** → alegi APK-ul → `classes.dex`: clasele aplicației apar ca `a`, `b`, `c…`. Doar modelele JSON își păstrează numele.
3. În `resources.arsc` și `AndroidManifest.xml` nu există nicio cheie API.
4. Din folderul proiectului, căutarea în istoric arată că nu e niciun secret în codul actual:
   ```
   git grep -n -E "AIza|BEGIN (EC |RSA )?PRIVATE KEY" HEAD
   ```
   (nu întoarce nimic). Pe tot istoricul, `git log -S AIzaSy --oneline` arată commit-urile vechi cu cheia revocată.
