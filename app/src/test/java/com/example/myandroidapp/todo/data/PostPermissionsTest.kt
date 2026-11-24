package com.example.myandroidapp.todo.data

import com.example.myandroidapp.auth.data.local.AuthProvider
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.UserRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drepturile pe roluri: USER, ADMIN, vizitator anonim, cont blocat. */
class PostPermissionsTest {

    private val ana = UserEntity(email = "ana@gmail.com", ownerKey = "1", displayName = "ana")
    private val ion = UserEntity(email = "ion@gmail.com", ownerKey = "2", displayName = "ion")
    private val admin = UserEntity(email = "admin@wanderlog.ro", ownerKey = "9", displayName = "admin", role = UserRole.ADMIN)
    private val guest = UserEntity(email = "vizitator-x@anonim.local", ownerKey = "7", displayName = "vizitator", provider = AuthProvider.ANONYMOUS)
    private val blocked = ana.copy(isBlocked = true)

    private val anaPost = Post(id = "p1", title = "Cheile Turzii", user_id = "1")
    private val anaDraft = anaPost.copy(id = "p2", status = PostStatus.DRAFT)
    private val anaHidden = anaPost.copy(id = "p3", hidden = true)
    private val anaPrivate = anaPost.copy(id = "p4", isPrivate = true)

    @Test
    fun `doar autorul isi editeaza postarea`() {
        assertTrue(PostPermissions.canEdit(anaPost, ana))
        assertFalse(PostPermissions.canEdit(anaPost, ion))
        assertFalse("adminul modereaza, nu rescrie", PostPermissions.canEdit(anaPost, admin))
        assertFalse(PostPermissions.canEdit(anaPost, guest))
        assertFalse(PostPermissions.canEdit(anaPost, null))
    }

    @Test
    fun `stergere - autorul si adminul, nu alt utilizator sau vizitatorul`() {
        assertTrue(PostPermissions.canDelete(anaPost, ana))
        assertTrue(PostPermissions.canDelete(anaPost, admin))
        assertFalse(PostPermissions.canDelete(anaPost, ion))
        assertFalse(PostPermissions.canDelete(anaPost, guest))
    }

    @Test
    fun `un cont blocat nu mai poate modifica nimic`() {
        assertFalse(PostPermissions.canEdit(anaPost, blocked))
        assertFalse(PostPermissions.canDelete(anaPost, blocked))
        assertFalse(PostPermissions.canPublish(blocked))
    }

    @Test
    fun `doar USER publica - adminul si vizitatorul nu`() {
        assertTrue(PostPermissions.canPublish(ana))
        assertFalse(PostPermissions.canPublish(admin))
        assertFalse(PostPermissions.canPublish(guest))
    }

    @Test
    fun `raportarea - doar USER, doar postarile altora`() {
        assertTrue(PostPermissions.canReport(anaPost, ion))
        assertFalse(PostPermissions.canReport(anaPost, ana))
        assertFalse(PostPermissions.canReport(anaPost, admin))
        assertFalse(PostPermissions.canReport(anaPost, guest))
    }

    @Test
    fun `moderarea e doar a adminului, seiful doar al utilizatorului`() {
        assertTrue(PostPermissions.canModerate(admin))
        assertFalse(PostPermissions.canModerate(ana))
        assertFalse(PostPermissions.canModerate(guest))
        assertTrue(PostPermissions.canOpenVault(ana))
        assertFalse(PostPermissions.canOpenVault(admin))
        assertFalse(PostPermissions.canOpenVault(guest))
    }

    @Test
    fun `ciorna o vede doar autorul`() {
        assertTrue(PostPermissions.canSee(anaDraft, ana))
        assertFalse(PostPermissions.canSee(anaDraft, ion))
        assertFalse(PostPermissions.canSee(anaDraft, admin))
        assertFalse(PostPermissions.canSee(anaDraft, guest))
    }

    @Test
    fun `postarea ascunsa de moderator - autorul si adminul`() {
        assertTrue(PostPermissions.canSee(anaHidden, ana))
        assertTrue(PostPermissions.canSee(anaHidden, admin))
        assertFalse(PostPermissions.canSee(anaHidden, ion))
        assertFalse(PostPermissions.canSee(anaHidden, guest))
    }

    @Test
    fun `datele private nu ajung la alte conturi sau roluri`() {
        assertTrue(PostPermissions.canSee(anaPrivate, ana))
        assertFalse(PostPermissions.canSee(anaPrivate, ion))
        assertFalse(PostPermissions.canSee(anaPrivate, admin))
    }

    @Test
    fun `postarea publica o vede oricine, si vizitatorul`() {
        listOf(ana, ion, admin, guest, null).forEach { assertTrue(PostPermissions.canSee(anaPost, it)) }
    }
}
