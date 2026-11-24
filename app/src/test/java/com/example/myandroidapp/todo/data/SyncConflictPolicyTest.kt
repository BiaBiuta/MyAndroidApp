package com.example.myandroidapp.todo.data

import com.example.myandroidapp.todo.data.SyncConflictPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ce se intampla cu o postare din coada cand serverul o refuza (rezolvarea conflictelor). */
class SyncConflictPolicyTest {

    private fun edit(status: Int) = SyncConflictPolicy.onServerError(status, deletedLocally = false)
    private fun delete(status: Int) = SyncConflictPolicy.onServerError(status, deletedLocally = true)

    @Test
    fun `modificat pe alt dispozitiv mai recent - castiga versiunea serverului`() {
        assertEquals(Outcome.KEEP_SERVER_VERSION, edit(409))
    }

    @Test
    fun `editata offline dar stearsa de moderator - devine ciorna privata`() {
        assertEquals(Outcome.SAVE_AS_PRIVATE_DRAFT, edit(410))
    }

    @Test
    fun `stearsa offline si deja disparuta de pe server - stergerea e reusita`() {
        assertEquals(Outcome.ALREADY_DELETED, delete(404))
        assertEquals(Outcome.ALREADY_DELETED, delete(410))
    }

    @Test
    fun `sesiune expirata sau server cu probleme - ramane Pending si se reincearca`() {
        listOf(401, 408, 429, 500, 502, 503).forEach {
            assertEquals("status $it", Outcome.RETRY_LATER, edit(it))
            assertEquals("status $it", Outcome.RETRY_LATER, delete(it))
        }
    }

    @Test
    fun `date invalide - Sincronizare esuata, utilizatorul corecteaza`() {
        listOf(400, 403, 413, 422).forEach { assertEquals("status $it", Outcome.FAILED, edit(it)) }
    }

    @Test
    fun `mesajele numesc postarea`() {
        assertEquals("Cheile Turzii", SyncConflictPolicy.displayTitle("  Cheile Turzii ", "x"))
        assertEquals("Apus pe Tâmpa", SyncConflictPolicy.displayTitle("", "Apus pe Tâmpa"))
        assertEquals("Postarea", SyncConflictPolicy.displayTitle("", ""))
        assertTrue(SyncConflictPolicy.keptServerVersionMessage("Tâmpa").contains("alt dispozitiv"))
        assertTrue(SyncConflictPolicy.savedAsDraftMessage("Tâmpa").contains("ciornă"))
    }
}
