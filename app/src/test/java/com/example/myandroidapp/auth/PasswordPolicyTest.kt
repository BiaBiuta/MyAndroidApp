package com.example.myandroidapp.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regulile parolei noi la inregistrare (OWASP / NIST) si indicatorul de putere. */
class PasswordPolicyTest {

    private val email = "ana.pop@gmail.com"

    @Test
    fun `o parola puternica trece toate regulile`() {
        val result = PasswordPolicy.check("Munti#Apuseni42", email)
        assertTrue(result.isValid)
        assertEquals(PasswordPolicy.Strength.STRONG, result.strength)
    }

    @Test
    fun `o parola lunga si valida e foarte puternica`() {
        val result = PasswordPolicy.check("Cheile#Turzii-2026!", email)
        assertTrue(result.isValid)
        assertEquals(PasswordPolicy.Strength.VERY_STRONG, result.strength)
    }

    @Test
    fun `parola scurta este respinsa`() {
        val result = PasswordPolicy.check("Ab1!xyz", email)
        assertFalse(result.isValid)
        assertFalse(PasswordPolicy.Rule.LENGTH in result.passed)
    }

    @Test
    fun `parolele des folosite sunt respinse`() {
        val result = PasswordPolicy.check("Parola12345!!", email)
        assertFalse(result.isValid)
        assertFalse(PasswordPolicy.Rule.NOT_COMMON in result.passed)
    }

    @Test
    fun `parola nu poate contine numele din e-mail`() {
        val result = PasswordPolicy.check("Xana.pop#Munte9", email)
        assertFalse(PasswordPolicy.Rule.NOT_PERSONAL in result.passed)
    }

    @Test
    fun `trei caractere identice la rand sunt respinse`() {
        val result = PasswordPolicy.check("Munti#Apuseni4222", email)
        assertFalse(PasswordPolicy.Rule.NO_REPEAT in result.passed)
    }

    @Test
    fun `parola prea lunga este respinsa`() {
        val result = PasswordPolicy.check("Aa1!" + "xyz".repeat(30), email)
        assertTrue(result.tooLong)
        assertFalse(result.isValid)
    }

    @Test
    fun `o parola slaba are indicatorul slab`() {
        assertEquals(PasswordPolicy.Strength.WEAK, PasswordPolicy.check("abc", email).strength)
    }
}
