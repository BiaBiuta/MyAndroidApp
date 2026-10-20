package com.example.myandroidapp.auth.data

import android.util.Log
import com.example.myandroidapp.auth.data.local.UserDao
import com.example.myandroidapp.auth.data.local.UserEntity
import com.example.myandroidapp.auth.data.local.UserRole
import com.example.myandroidapp.core.PasswordHasher
import com.example.myandroidapp.core.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.myandroidapp.core.data.UserPreferencesRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.Locale

/**
 * Conturile utilizatorilor, tinute in baza de date locala (tabela users):
 * rolul (USER / ADMIN) si credentialele (hash-ul parolei, pentru login offline).
 * Nu schimba login-ul: dupa orice login reusit, contul sesiunii curente este
 * inregistrat aici (registerSession), iar rolul lui decide ce vede in aplicatie.
 */
class RoleRepository(
    private val userDao: UserDao,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    companion object {
        private const val TAG = "RoleRepository"

        // Conturile care sunt administratori implicit (restul pot fi promovati de un admin)
        val DEFAULT_ADMIN_EMAILS = setOf("admin@wanderlog.ro")

        const val BLOCKED_MESSAGE =
            "Acest cont a fost blocat de un administrator."

        fun normalize(email: String) = email.trim().lowercase(Locale.ROOT)
    }

    /** Contul sesiunii curente, cu rolul lui (null = nicio sesiune / vizitator). Se actualizeaza live. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val currentUser: Flow<UserEntity?> = userPreferencesRepository.userPreferencesStream
        .map { prefs -> if (prefs.token.isEmpty() || prefs.email.isBlank()) "" else normalize(prefs.email) }
        .distinctUntilChanged()
        .flatMapLatest { email -> if (email.isEmpty()) flowOf(null) else userDao.observeByEmail(email) }

    suspend fun currentUserOnce(): UserEntity? = currentUser.first()

    fun observeAllUsers(): Flow<List<UserEntity>> = userDao.observeAll()

    /** Inregistreaza (sau actualizeaza) in baza locala contul sesiunii curente. */
    suspend fun registerSession(prefs: UserPreferences) {
        if (prefs.token.isEmpty() || prefs.email.isBlank()) return
        val email = normalize(prefs.email)
        val existing = userDao.findByEmail(email)
        val user = existing?.copy(ownerKey = prefs.ownerKey, lastLoginAt = System.currentTimeMillis())
            ?: UserEntity(
                email = email,
                ownerKey = prefs.ownerKey,
                displayName = email.substringBefore("@"),
                role = if (email in DEFAULT_ADMIN_EMAILS) UserRole.ADMIN else UserRole.USER
            )
        userDao.upsert(user)
    }

    // ---------- Credentiale (login offline) ----------

    /** Rezultatul verificarii offline a parolei, in baza de date locala. */
    enum class OfflineCheck { VALID, WRONG_PASSWORD, UNKNOWN_ACCOUNT, BLOCKED }

    /**
     * Salveaza in baza locala hash-ul parolei pentru un login reusit (online sau primul login
     * offline al unui cont nou), ca urmatoarele login-uri offline sa poata verifica parola.
     */
    suspend fun saveCredentials(email: String, ownerKey: String, passwordHash: String) {
        val normalized = normalize(email)
        val existing = userDao.findByEmail(normalized)
        val user = existing?.copy(
            ownerKey = ownerKey,
            passwordHash = passwordHash,
            lastLoginAt = System.currentTimeMillis()
        ) ?: UserEntity(
            email = normalized,
            ownerKey = ownerKey,
            displayName = normalized.substringBefore("@"),
            role = if (normalized in DEFAULT_ADMIN_EMAILS) UserRole.ADMIN else UserRole.USER,
            passwordHash = passwordHash
        )
        userDao.upsert(user)
    }

    /** Rezultatul inregistrarii unui cont nou. */
    enum class RegisterResult { CREATED, ALREADY_EXISTS, BLOCKED }

    suspend fun findAccount(email: String): UserEntity? = userDao.findByEmail(normalize(email))

    /**
     * Inregistrare: creeaza contul in baza de date locala, cu hash-ul parolei.
     * Un cont care a fost folosit in versiunea anterioara a aplicatiei (fara parola salvata)
     * isi poate seta acum parola o singura data; un cont cu parola deja salvata e refuzat.
     */
    suspend fun registerAccount(email: String, passwordHash: String): RegisterResult {
        val existing = userDao.findByEmail(normalize(email))
        if (existing != null) {
            if (existing.isBlocked) return RegisterResult.BLOCKED
            if (existing.passwordHash.isNotEmpty()) return RegisterResult.ALREADY_EXISTS
        }
        saveCredentials(email, existing?.ownerKey ?: email, passwordHash)
        return RegisterResult.CREATED
    }

    /** true daca un admin a blocat contul cu acest e-mail. */
    suspend fun isBlocked(email: String): Boolean =
        userDao.findByEmail(normalize(email))?.isBlocked == true

    /**
     * Verifica parola fara server, fata de hash-ul din baza locala:
     *  - VALID: contul exista si parola e corecta
     *  - WRONG_PASSWORD: contul exista, parola e gresita -> login refuzat
     *  - UNKNOWN_ACCOUNT: contul nu a mai fost folosit pe acest telefon
     *  - BLOCKED: contul a fost blocat de un admin
     */
    suspend fun checkOffline(email: String, password: String): OfflineCheck {
        val user = userDao.findByEmail(normalize(email)) ?: return OfflineCheck.UNKNOWN_ACCOUNT
        if (user.isBlocked) return OfflineCheck.BLOCKED
        if (user.passwordHash.isEmpty()) return OfflineCheck.UNKNOWN_ACCOUNT
        val ok = withContext(Dispatchers.Default) { PasswordHasher.verify(password, user.passwordHash) }
        return if (ok) OfflineCheck.VALID else OfflineCheck.WRONG_PASSWORD
    }

    // ---------- Administrare (doar ADMIN) ----------

    private suspend fun requireAdmin(): UserEntity? =
        currentUserOnce()?.takeIf { it.role == UserRole.ADMIN && !it.isBlocked }

    /** Schimba rolul unui utilizator. Intoarce un mesaj de eroare sau null la succes. */
    suspend fun changeRole(email: String, newRole: String): String? {
        val admin = requireAdmin() ?: return "Doar administratorii pot schimba rolurile."
        val target = userDao.findByEmail(email) ?: return "Utilizatorul nu mai există."
        if (target.role == UserRole.ADMIN && newRole == UserRole.USER &&
            !target.isBlocked && userDao.countActiveAdmins() <= 1
        ) {
            return "Trebuie să rămână cel puțin un administrator activ."
        }
        Log.d(TAG, "${admin.email} changed role of ${target.email} to $newRole")
        userDao.setRole(email, newRole)
        return null
    }

    /** Blocheaza / deblocheaza un cont. Intoarce un mesaj de eroare sau null la succes. */
    suspend fun setBlocked(email: String, blocked: Boolean): String? {
        val admin = requireAdmin() ?: return "Doar administratorii pot bloca utilizatori."
        if (admin.email == email) return "Nu vă puteți bloca propriul cont."
        val target = userDao.findByEmail(email) ?: return "Utilizatorul nu mai există."
        if (blocked && target.role == UserRole.ADMIN && userDao.countActiveAdmins() <= 1) {
            return "Trebuie să rămână cel puțin un administrator activ."
        }
        userDao.setBlocked(email, blocked)
        return null
    }
}
