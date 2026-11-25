package com.example.myandroidapp.core.logging

/**
 * Curata un mesaj de log de date sensibile INAINTE sa ajunga in logcat.
 *
 * De ce: logcat-ul poate fi citit cu `adb logcat` de oricine are telefonul conectat la un PC,
 * apare in rapoartele de eroare (bug reports) si, pe telefoane vechi / cu root, chiar de alte
 * aplicatii. Un token din log = sesiunea furata; un e-mail sau coordonatele GPS = date personale.
 *
 * Fara dependente Android, ca sa poata fi verificat cu teste unitare (LogRedactorTest).
 * Regulile se aplica in ordine: intai secretele (token, parole), apoi datele personale.
 */
object LogRedactor {

    const val TOKEN = "[token ascuns]"
    const val HIDDEN = "[ascuns]"
    const val LOCATION = "[locație ascunsă]"
    const val BINARY = "[date binare ascunse]"

    private data class Rule(val regex: Regex, val replacement: String)

    private val rules = listOf(
        // 1. poze / fisiere trimise ca Base64 (pot fi poze din seiful privat)
        Rule(Regex("data:[a-z]+/[a-z0-9.+-]+;base64,[A-Za-z0-9+/=]+"), BINARY),

        // 2. token-uri JWT (header.payload.semnatura): serverul nostru emite tokenuri "eyJ..."
        Rule(Regex("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"), TOKEN),
        // sesiunile create offline au tokenuri locale "local-..."
        Rule(Regex("local-[A-Za-z0-9_-]{8,}"), TOKEN),
        // antetul HTTP
        Rule(Regex("(?i)(Bearer)\\s+(?!\\[token)[^\\s\"',;]+"), "$1 $TOKEN"),

        // 3. campuri secrete, si in JSON ("password":"x") si in toString-ul claselor (password=x)
        Rule(
            Regex(
                "(?i)(\"?\\b(?:token|access_token|accessToken|refresh_token|password|passwordHash|newPassword|" +
                    "currentPassword|secret|pairingKey|pairing_key|authorization|apiKey|api_key|cookie|signature)\\b\"?\\s*[:=]\\s*)" +
                    // "Authorization: Bearer ..." e tratat mai sus (ramane "Bearer [token ascuns]")
                    "(?!Bearer\\b)(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\s,;)}\\]]+)"
            ),
            "$1$HIDDEN"
        ),

        // 4. continutul personal al postarilor / comentariilor (JSON si toString)
        Rule(
            Regex(
                "(\"?\\b(?:title|description|text|place|reason|notes|author|user_first_name|user_last_name|" +
                    "photo|photo_url|photoPath|user_profile_photo|gallery)\\b\"?\\s*[:=]\\s*)" +
                    "(\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^\\]]*\\]|[^,;)}\\]]*)"
            ),
            "$1$HIDDEN"
        ),

        // 5. coordonate: campuri "latitude=46.77" / "\"longitude\":23.59" / "Latitude: 46.77"
        Rule(
            Regex("(?i)(\"?\\b(?:lat|latitude|lon|lng|longitude)\\b\"?\\s*[:=]\\s*)-?\\d+(?:\\.\\d+)?"),
            "$1$HIDDEN"
        ),
        // ... si perechi "46.770439,23.591423" (ex. android.location.Location.toString())
        Rule(Regex("-?\\d{1,3}\\.\\d{3,}\\s*,\\s*-?\\d{1,3}\\.\\d{3,}"), LOCATION),

        // 6. adrese de e-mail: se pastreaza prima litera si domeniul (util la depanare, fara identitate)
        Rule(
            Regex("\\b([A-Za-z0-9])[A-Za-z0-9._%+-]*@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})\\b"),
            "$1***@$2"
        ),

        // 7. orice alt sir lung Base64 (chei, poze, payload-uri criptate)
        Rule(Regex("[A-Za-z0-9+/_-]{120,}={0,2}"), BINARY)
    )

    /** Mesajul fara date sensibile. Nu arunca niciodata exceptii (logarea nu are voie sa strice aplicatia). */
    fun redact(message: String?): String {
        if (message.isNullOrEmpty()) return message.orEmpty()
        return try {
            rules.fold(message) { text, rule -> rule.regex.replace(text, rule.replacement) }
        } catch (e: Exception) {
            "[mesaj de log ascuns]"
        }
    }
}
