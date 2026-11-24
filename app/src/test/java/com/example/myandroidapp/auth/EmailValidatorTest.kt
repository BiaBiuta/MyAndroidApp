package com.example.myandroidapp.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Validarea in timp real a e-mailului din formularul de login / inregistrare. */
class EmailValidatorTest {

    @Test
    fun `adrese valide sunt acceptate`() {
        listOf("ana@gmail.com", "ana.pop+calatorii@ubbcluj.ro", "a_b-c@sub.domeniu.org", "  ana@gmail.com  ")
            .forEach { assertTrue("ar trebui sa fie valida: $it", EmailValidator.isValid(it)) }
    }

    @Test
    fun `adrese invalide sunt respinse`() {
        listOf("ana", "ana@", "@gmail.com", "ana@gmail", "ana gmail@x.ro", "ana@@gmail.com", "ana@.com")
            .forEach { assertFalse("ar trebui sa fie invalida: $it", EmailValidator.isValid(it)) }
    }

    @Test
    fun `campul gol nu afiseaza eroare cat timp utilizatorul scrie`() {
        assertNull(EmailValidator.error(""))
        assertNull(EmailValidator.error("   "))
    }

    @Test
    fun `mesajele de eroare sunt specifice`() {
        assertEquals(EmailValidator.INVALID_FORMAT, EmailValidator.error("ana@gmail"))
        val tooLong = "a".repeat(250) + "@x.ro"
        assertEquals(EmailValidator.TOO_LONG, EmailValidator.error(tooLong))
        assertNull(EmailValidator.error("ana@gmail.com"))
    }
}
