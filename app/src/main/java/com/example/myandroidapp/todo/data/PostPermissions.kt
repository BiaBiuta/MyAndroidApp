package com.example.myandroidapp.todo.data

import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.isAdmin
import com.example.myandroidapp.auth.data.local.isGuest
import com.example.myandroidapp.auth.data.local.isRegularUser

/**
 * Regulile de acces pe roluri, intr-un singur loc (folosite de ViewModel-uri si verificate de
 * PostPermissionsTest). UI-ul ascunde butoanele, dar decizia reala se ia aici.
 *
 *  - USER: publica, editeaza si sterge DOAR postarile proprii, raporteaza postarile altora,
 *          are sectiunea privata (seiful);
 *  - ADMIN: nu publica; modereaza (ascunde / sterge) orice postare;
 *  - Vizitator anonim: doar citeste feed-ul public.
 *  - Un cont blocat nu mai poate modifica nimic.
 */
//exista doar o instanta globala si functiile pot sa fie apelate de oriunde
object PostPermissions {

    /** Cine vede postarea in feed: ciornele doar autorul; cele ascunse de moderator autorul si adminii. */
    fun canSee(post: Post, user: UserEntity?): Boolean {
        val mine = isOwner(post, user)
        if (post.isPrivate) return mine
        return (post.status != PostStatus.DRAFT || mine) && (!post.hidden || mine || user?.isAdmin == true)
    }
    /**cine poate publica - doar utilizatorul */
    fun canPublish(user: UserEntity?): Boolean =
        user != null && user.isRegularUser && !user.isBlocked

    /** Doar autorul isi poate edita postarea  */
    fun canEdit(post: Post, user: UserEntity?): Boolean =
        user != null && !user.isBlocked && !user.isGuest && isOwner(post, user)

    /** ADMIN: orice postare. USER: doar postarile proprii. */
    fun canDelete(post: Post, user: UserEntity?): Boolean =
        user != null && !user.isBlocked && !user.isGuest && (user.isAdmin || isOwner(post, user))

    /** Doar USER (nu admin, nu vizitator) poate raporta postarile altora. */
    fun canReport(post: Post, user: UserEntity?): Boolean =
        user != null && user.isRegularUser && !user.isBlocked && post.user_id != user.ownerKey

    /** Ascunderea / reafisarea oricarei postari si statisticile: doar ADMIN. */
    fun canModerate(user: UserEntity?): Boolean =
        user != null && user.isAdmin && !user.isBlocked

    /** Seiful privat: doar USER cu cont (vizitatorul nu are date personale, adminul nu are seif). */
    fun canOpenVault(user: UserEntity?): Boolean =
        user != null && user.isRegularUser

    private fun isOwner(post: Post, user: UserEntity?): Boolean =
        user != null && post.user_id.isNotEmpty() && post.user_id == user.ownerKey
}
