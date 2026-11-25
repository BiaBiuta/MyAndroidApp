package com.example.myandroidapp.auth.data

import com.example.myandroidapp.core.logging.SafeLog
import com.example.myandroidapp.auth.data.local.AuthProvider
import com.example.myandroidapp.auth.data.remote.RemoteUser
import com.example.myandroidapp.auth.data.remote.UserService
import com.example.myandroidapp.core.data.remote.OfflineException
import com.example.myandroidapp.core.data.remote.apiCall
import com.example.myandroidapp.core.data.remote.toUserMessage
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
    private val userPreferencesRepository: UserPreferencesRepository,
    private val userService: UserService
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
                role = if (email in DEFAULT_ADMIN_EMAILS) UserRole.ADMIN else UserRole.USER,
                provider = prefs.provider
            )
        userDao.upsert(user)
    }

    // ---------- Google / Apple / anonim (conturi locale, fara parola) ----------

    /** Rezultatul unui login cu furnizor: contul local incarcat / creat, sau refuzat. */
    sealed interface ProviderSignIn {
        data class Success(val user: UserEntity, val isNewAccount: Boolean) : ProviderSignIn
        data object Blocked : ProviderSignIn
    }

    /**
     * Login cu Google, Apple sau anonim: cauta contul in baza de date locala dupa e-mail
     * si il incarca (cu rolul si datele lui); daca nu exista, il creeaza.
     * Un cont existent creat cu e-mail si parola, folosit acum cu Google, ramane acelasi cont
     * (aceleasi postari), ca la "account linking".
     */
    suspend fun signInWithProvider(email: String, provider: String): ProviderSignIn {
        val normalized = normalize(email)
        val existing = userDao.findByEmail(normalized)
        if (existing?.isBlocked == true) return ProviderSignIn.Blocked
        val user = existing?.copy(lastLoginAt = System.currentTimeMillis())
            ?: UserEntity(
                email = normalized,
                ownerKey = normalized,
                displayName = if (provider == AuthProvider.ANONYMOUS) "Vizitator" else normalized.substringBefore("@"),
                role = if (normalized in DEFAULT_ADMIN_EMAILS) UserRole.ADMIN else UserRole.USER,
                provider = provider
            )
        userDao.upsert(user)
        return ProviderSignIn.Success(user, isNewAccount = existing == null)
    }

    /** Conturile Google / Apple folosite deja pe acest telefon (pentru selectorul de cont). */
    suspend fun accountsFor(provider: String): List<UserEntity> = userDao.findByProvider(provider)

    // ---------- Credentiale (login offline) ----------

    /** Rezultatul verificarii offline a parolei, in baza de date locala. */
    enum class OfflineCheck { VALID, WRONG_PASSWORD, UNKNOWN_ACCOUNT, BLOCKED }

    /**
     * Salveaza in baza locala hash-ul parolei pentru un login reusit (online sau primul login
     * offline al unui cont nou), ca urmatoarele login-uri offline sa poata verifica parola.
     */
    suspend fun saveCredentials(
        email: String,
        ownerKey: String,
        passwordHash: String,
        // trimise de server la login: rolul si metoda de autentificare sunt cele de pe server
        role: String? = null,
        provider: String? = null
    ) {
        val normalized = normalize(email)
        val existing = userDao.findByEmail(normalized)
        val user = existing?.copy(
            ownerKey = ownerKey,
            // conturile Google / Apple / anonim nu au parola: pastram ce exista
            passwordHash = passwordHash.ifEmpty { existing.passwordHash },
            role = role ?: existing.role,
            provider = provider ?: existing.provider,
            isBlocked = if (role != null) false else existing.isBlocked, // serverul a acceptat login-ul
            lastLoginAt = System.currentTimeMillis()
        ) ?: UserEntity(
            email = normalized,
            ownerKey = ownerKey,
            displayName = normalized.substringBefore("@"),
            role = role ?: if (normalized in DEFAULT_ADMIN_EMAILS) UserRole.ADMIN else UserRole.USER,
            passwordHash = passwordHash,
            provider = provider ?: AuthProvider.EMAIL
        )
        userDao.upsert(user)
    }

    suspend fun findAccount(email: String): UserEntity? = userDao.findByEmail(normalize(email))

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
    // Rolurile si blocarea se schimba PE SERVER (sursa de adevar), apoi se copiaza in baza locala;
    // lista din ecranul Administrare se actualizeaza singura (Flow din Room).

    private suspend fun requireAdmin(): UserEntity? =
        currentUserOnce()?.takeIf { it.role == UserRole.ADMIN && !it.isBlocked }

    /**
     * Aduce lista de utilizatori de pe server in baza locala. Intoarce un mesaj de eroare
     * (ex. offline: se vede lista salvata) sau null la succes.
     */
    suspend fun refreshUsersFromServer(): String? {
        requireAdmin() ?: return "Doar administratorii pot vedea utilizatorii."
        return try {
            apiCall { userService.list() }.forEach { saveRemoteUser(it) }
            null
        } catch (e: OfflineException) {
            "Ești offline: se afișează utilizatorii salvați pe telefon."
        } catch (e: Exception) {
            e.toUserMessage("Lista de utilizatori nu a putut fi actualizată.")
        }
    }

    /** Schimba rolul unui utilizator. Intoarce un mesaj de eroare sau null la succes. */
    suspend fun changeRole(email: String, newRole: String): String? {
        requireAdmin() ?: return "Doar administratorii pot schimba rolurile."
        val target = userDao.findByEmail(email) ?: return "Utilizatorul nu mai există."
        if (newRole == UserRole.ADMIN && target.provider == AuthProvider.ANONYMOUS) {
            return "Un vizitator anonim nu poate deveni administrator."
        }
        return updateOnServer(target, mapOf("role" to newRole))
    }

    /** Blocheaza / deblocheaza un cont. Intoarce un mesaj de eroare sau null la succes. */
    suspend fun setBlocked(email: String, blocked: Boolean): String? {
        val admin = requireAdmin() ?: return "Doar administratorii pot bloca utilizatori."
        if (admin.email == email) return "Nu vă puteți bloca propriul cont."
        val target = userDao.findByEmail(email) ?: return "Utilizatorul nu mai există."
        return updateOnServer(target, mapOf("blocked" to blocked))
    }

    private suspend fun updateOnServer(target: UserEntity, changes: Map<String, Any>): String? {
        // conturile create doar offline nu exista inca pe server
        val serverId = target.ownerKey.toIntOrNull()
            ?: return "Contul ${target.email} nu există încă pe server (a fost folosit doar offline)."
        return try {
            val updated = apiCall { userService.update(serverId, changes) }
            saveRemoteUser(updated)
            SafeLog.d(TAG, "${target.email} actualizat pe server: $changes")
            null
        } catch (e: OfflineException) {
            "Ești offline. Rolurile și blocarea se pot schimba doar cu conexiune la server."
        } catch (e: Exception) {
            e.toUserMessage("Modificarea nu a putut fi salvată pe server.")
        }
    }

    /** Copiaza un cont venit de pe server in baza locala (pastrand hash-ul parolei, daca exista). */
    private suspend fun saveRemoteUser(remote: RemoteUser) {
        val email = normalize(remote.email)
        val existing = userDao.findByEmail(email)
        userDao.upsert(
            existing?.copy(
                ownerKey = remote.id.toString(),
                role = remote.role ?: existing.role,
                provider = remote.provider ?: existing.provider,
                isBlocked = remote.blocked
            ) ?: UserEntity(
                email = email,
                ownerKey = remote.id.toString(),
                displayName = if (remote.provider == AuthProvider.ANONYMOUS) "Vizitator" else email.substringBefore("@"),
                role = remote.role ?: UserRole.USER,
                provider = remote.provider ?: AuthProvider.EMAIL,
                isBlocked = remote.blocked
            )
        )
    }
}
