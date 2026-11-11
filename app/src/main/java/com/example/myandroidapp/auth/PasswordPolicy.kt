package com.example.myandroidapp.auth

/**
 * Regulile pentru o parola NOUA (la Inregistrare). La Autentificare nu le aplicam:
 * nu blocam conturile existente si nu ii aratam unui atacator ce reguli are aplicatia.
 *
 * Bazat pe recomandarile OWASP (Authentication Cheat Sheet) si NIST SP 800-63B:
 * lungime minima mare, lungime maxima generoasa, interzicerea parolelor des folosite
 * si a celor care contin datele contului.
 */
object PasswordPolicy {

    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 64

    /** Fiecare regula are textul afisat in lista de sub campul de parola. */
    enum class Rule(val label: String) {
        LENGTH("Cel puțin $MIN_LENGTH caractere"),
        UPPERCASE("O literă mare (A–Z)"),
        LOWERCASE("O literă mică (a–z)"),
        DIGIT("O cifră (0–9)"),
        SPECIAL("Un simbol, de exemplu ! @ # ? %"),
        NO_REPEAT("Fără 3 caractere identice la rând"),
        NOT_PERSONAL("Nu conține numele din adresa de e-mail"),
        NOT_COMMON("Nu este o parolă des folosită")
    }

    enum class Strength(val label: String, val progress: Float) {
        WEAK("Slabă", 0.25f),
        MEDIUM("Medie", 0.5f),
        STRONG("Puternică", 0.75f),
        VERY_STRONG("Foarte puternică", 1f)
    }

    data class Result(val passed: Set<Rule>, val tooLong: Boolean, val length: Int) {
        val isValid: Boolean get() = passed.size == Rule.entries.size && !tooLong

        val strength: Strength
            get() = when {
                isValid && length >= 16 -> Strength.VERY_STRONG
                isValid -> Strength.STRONG
                passed.size >= Rule.entries.size - 2 -> Strength.MEDIUM
                else -> Strength.WEAK
            }
    }

    /**
     * Fragmente din cele mai folosite parole (liste publice de parole compromise).
     * O parola care contine oricare dintre ele e respinsa, de ex. "Parola123!" sau "Qwerty2024!!".
     */
    private val COMMON_FRAGMENTS = listOf(
        "password", "parola", "passw0rd", "qwerty", "azerty", "asdf", "zxcv", "123456", "12345",
        "654321", "111111", "000000", "abc123", "abcdef", "admin", "welcome", "letmein",
        "iloveyou", "monkey", "dragon", "football", "baseball", "superman", "batman",
        "sunshine", "princess", "master", "secret", "login", "romania", "bucuresti",
        "wanderlog", "android"
    )

    fun check(password: String, email: String): Result {
        val lower = password.lowercase()
        val emailName = email.trim().substringBefore("@").lowercase()
        val passed = buildSet {
            if (password.length >= MIN_LENGTH) add(Rule.LENGTH)
            if (password.any { it.isUpperCase() }) add(Rule.UPPERCASE)
            if (password.any { it.isLowerCase() }) add(Rule.LOWERCASE)
            if (password.any { it.isDigit() }) add(Rule.DIGIT)
            if (password.any { !it.isLetterOrDigit() && !it.isWhitespace() }) add(Rule.SPECIAL)
            if (!hasTripleRepeat(password)) add(Rule.NO_REPEAT)
            // numele din e-mail (de ex. "ana.pop" din ana.pop@gmail.com) nu are voie in parola
            if (emailName.length < 3 || !lower.contains(emailName)) add(Rule.NOT_PERSONAL)
            if (COMMON_FRAGMENTS.none { lower.contains(it) }) add(Rule.NOT_COMMON)
        }
        return Result(passed = passed, tooLong = password.length > MAX_LENGTH, length = password.length)
    }

    private fun hasTripleRepeat(password: String): Boolean =
        password.windowed(3).any { it[0] == it[1] && it[1] == it[2] }
}
