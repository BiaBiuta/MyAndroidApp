package com.example.myandroidapp.core

import android.os.Build
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash pentru parola, ca sa putem verifica local (fara server) parola la login-ul offline
 * si la intrarea in sectiunea privata. Parola in clar NU se salveaza niciodata.
 *
 * - PBKDF2-HMAC-SHA256 cu 310.000 de iteratii (recomandarea OWASP) si salt aleator de 16 octeti;
 *   pe Android 7 (API 24-25), unde SHA256 nu exista pentru PBKDF2, se foloseste SHA1.
 * - Formatul salvat contine algoritmul si numarul de iteratii, deci se pot mari pe viitor:
 *   "pbkdf2$<algoritm>$<iteratii>$<salt base64>$<hash base64>"
 * - Hash-urile vechi ("<salt>:<hash>", SHA1 cu 10.000 de iteratii) sunt inca acceptate si
 *   se inlocuiesc automat cu cele noi la urmatorul login.
 */
object PasswordHasher {
    private const val ITERATIONS = 310_000
    private const val KEY_LENGTH = 256
    private const val SALT_BYTES = 16
    private const val PREFIX = "pbkdf2"
    private const val SEP = '$'

    private const val LEGACY_ITERATIONS = 10_000
    private const val LEGACY_ALGORITHM = "PBKDF2WithHmacSHA1"

    private val ALGORITHM =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "PBKDF2WithHmacSHA256" else LEGACY_ALGORITHM

    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val hash = pbkdf2(password, salt, ALGORITHM, ITERATIONS)
        return listOf(PREFIX, ALGORITHM, ITERATIONS.toString(), encode(salt), encode(hash))
            .joinToString(SEP.toString())
    }

    fun verify(password: String, stored: String): Boolean = try {
        verifyOrThrow(password, stored)
    } catch (e: Exception) {
        false // hash corupt sau algoritm necunoscut -> parola nu e acceptata
    }

    private fun verifyOrThrow(password: String, stored: String): Boolean {
        val parts = stored.split(SEP)
        return when {
            // format nou: pbkdf2$algoritm$iteratii$salt$hash
            parts.size == 5 && parts[0] == PREFIX -> {
                val iterations = parts[2].toIntOrNull() ?: return false
                constantTimeEquals(
                    pbkdf2(password, decode(parts[3]), parts[1], iterations),
                    decode(parts[4])
                )
            }
            // format vechi: salt:hash
            else -> {
                val legacy = stored.split(":")
                if (legacy.size != 2) return false
                constantTimeEquals(
                    pbkdf2(password, decode(legacy[0]), LEGACY_ALGORITHM, LEGACY_ITERATIONS),
                    decode(legacy[1])
                )
            }
        }
    }

    private fun pbkdf2(password: String, salt: ByteArray, algorithm: String, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH)
        return try {
            SecretKeyFactory.getInstance(algorithm).generateSecret(spec).encoded
        } finally {
            spec.clearPassword() // stergem copia parolei din memorie
        }
    }

    /** Comparatie in timp constant: durata nu dezvaluie cate caractere se potrivesc. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)
}
