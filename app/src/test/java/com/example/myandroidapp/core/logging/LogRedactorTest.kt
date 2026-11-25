package com.example.myandroidapp.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mesajele de log de mai jos sunt exact cele pe care le scria aplicatia inainte de SafeLog
 * (WebSocket, locatie, administrare). Dupa curatare nu trebuie sa mai contina nimic sensibil.
 */
class LogRedactorTest {

    private val jwt =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOjEsInJvbGUiOiJVU0VSIiwiZXhwIjoxNzk1NTU1NDcxfQ.Qx3k9vR2dZbq0aYw7oLx1J0zFvT6YbqkP3s8mWnE2cA"

    private fun assertHidden(raw: String, vararg secrets: String): String {
        val clean = LogRedactor.redact(raw)
        secrets.forEach { assertFalse("„$it” a ramas in: $clean", clean.contains(it)) }
        return clean
    }

    @Test
    fun `token JWT si antetul Authorization`() {
        assertEquals("Authorization: Bearer ${LogRedactor.TOKEN}", assertHidden("Authorization: Bearer $jwt", jwt, "eyJ"))
        assertHidden("""{"token":"$jwt","user":{"id":3}}""", jwt)
        assertHidden("TokenHolder(token=$jwt)", jwt)
        assertHidden("sesiune offline local-3f2a9c1e5b7d4e8a", "local-3f2a9c1e5b7d4e8a")
    }

    @Test
    fun `parole si chei`() {
        assertHidden("""{"email":"ana@gmail.com","password":"Munti#Apuseni42"}""", "Munti#Apuseni42")
        assertHidden("UserPreferences(passwordHash=pbkdf2\$120000\$abc\$def, pairingKey=enc1:xyz)", "pbkdf2", "enc1:xyz")
        assertHidden("""{"currentPassword":"Veche#1234","newPassword":"Noua#56789"}""", "Veche#1234", "Noua#56789")
    }

    @Test
    fun `e-mailul pastreaza doar prima litera si domeniul`() {
        val clean = assertHidden("bianca.furtos17@gmail.com actualizat pe server: {role=ADMIN}", "bianca.furtos17")
        assertTrue(clean, clean.startsWith("b***@gmail.com"))
        assertTrue("rolul nu e o data personala", clean.contains("role=ADMIN"))
    }

    @Test
    fun `coordonatele GPS din Location si din Post`() {
        assertHidden(
            "lastLocation Location[fused 46.770439,23.591423 hAcc=12.0 et=+2d3h vAcc=3.0]",
            "46.770439", "23.591423"
        )
        assertHidden("Selected location updated: Location(latitude=46.7704, longitude=23.5914)", "46.7704", "23.5914")
        assertHidden("Latitude: 46.770439, Longitude: 23.591423", "46.770439", "23.591423")
    }

    @Test
    fun `mesajul WebSocket cu o postare`() {
        val ws = """{"event":"created","payload":{"item":{"id":"p1","title":"Cheile Turzii",""" +
            """"description":"Am urcat cu Ana, ne-am cazat la pensiunea lui Ion","place":"Turda",""" +
            """"location":{"latitude":46.5642,"longitude":23.6814},"user_first_name":"bianca"}}}"""
        val clean = assertHidden(ws, "Cheile Turzii", "pensiunea", "Turda", "46.5642", "23.6814", "bianca")
        assertTrue("tipul evenimentului si id-ul raman, utile la depanare", clean.contains("\"event\":\"created\"") && clean.contains("p1"))
    }

    @Test
    fun `postarea afisata cu toString`() {
        assertHidden(
            "Item event collected Post(id=p1, title=Apus pe Tâmpa, description=Seara cu prietenii, place=Brașov, rating=5)",
            "Apus pe Tâmpa", "Seara cu prietenii", "Brașov"
        )
    }

    @Test
    fun `pozele Base64 nu ajung in log`() {
        val photo = "data:image/jpeg;base64," + "A".repeat(400)
        assertEquals("poza=${LogRedactor.BINARY}", LogRedactor.redact("poza=$photo"))
        assertHidden("cheie " + "Zm9v".repeat(50), "Zm9vZm9v")
    }

    @Test
    fun `mesajele obisnuite raman neschimbate`() {
        listOf(
            "init",
            "server indisponibil, login verificat in baza locala",
            "sincronizare: 3 postari trimise, 0 esuate",
            "https://tiles.openfreemap.org -> HTTP 403"
        ).forEach { assertEquals(it, LogRedactor.redact(it)) }
        assertEquals("", LogRedactor.redact(null))
    }
}
