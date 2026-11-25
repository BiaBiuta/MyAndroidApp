# WanderLog – App Hardening

Măsura din lista „Bonus Security Feature Ideas → App Hardening”: **Secure logging**. Mai jos: atacul, apărarea, unde e în cod și cum se demonstrează.

## Secure logging

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
- Cele două loguri care scriau postări întregi (`PostWsClient`, `PostRepository`) scriu acum doar tipul evenimentului, id-ul și dimensiunea.

### Dovada
- Testul unitar `app/src/test/.../core/logging/LogRedactorTest.kt` (8 teste) folosește exact mesajele de mai sus: JWT, parole, e-mail, GPS, WebSocket, Base64.
- **Demo** (build de debug, telefonul conectat prin cablu): Android Studio → fereastra **Logcat** → filtrul `tag:WL/Demo`. În aplicație: **Profil → cardul „Demo securitate: loguri”** (există doar în debug).
  1. **Atacul:** comutatorul „Ascunde datele sensibile” **oprit** → „Scrie în log date de test”. În Logcat apar e-mailul, tokenul de sesiune, coordonatele, textul unei postări și parolele.
  2. **Apărarea:** comutatorul **pornit** → același buton. Apar `b***@gmail.com`, `Bearer [token ascuns]`, `[locație ascunsă]`, `"title":[ascuns]`, `"newPassword":[ascuns]`.
  3. În release cardul nu există, iar curățarea nu poate fi oprită (`SafeLog.redactionEnabled`).
