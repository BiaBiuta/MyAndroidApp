package com.example.myandroidapp.auth

/**
 * Validarea adresei de e-mail din formulare (login, inregistrare, alegerea contului Google / Apple).
 * Acelasi tipar ca android.util.Patterns.EMAIL_ADDRESS, dar fara dependente Android,
 * ca sa poata fi verificat cu teste unitare simple (EmailValidatorTest).
 */
object EmailValidator {
    const val MAX_LENGTH = 254

    private val PATTERN = Regex(
        "[a-zA-Z0-9+._%\\-]{1,256}" +
            "@" +
            "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
            "(\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+"
    )

    const val TOO_LONG = "Adresa de e-mail este prea lungă"
    const val INVALID_FORMAT = "Format de e-mail invalid"

    /** true = adresa (fara spatiile de la capete) are un format valid. */
    fun isValid(email: String): Boolean {
        val trimmed = email.trim()
        return trimmed.length <= MAX_LENGTH && PATTERN.matches(trimmed)
    }

    /**
     * Mesajul afisat in timp real sub camp, sau null daca nu e nimic de semnalat.
     * Un camp gol nu e o eroare cat timp utilizatorul inca scrie.
     */
    fun error(email: String): String? {
        val trimmed = email.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.length > MAX_LENGTH -> TOO_LONG
            !PATTERN.matches(trimmed) -> INVALID_FORMAT
            else -> null
        }
    }
}
