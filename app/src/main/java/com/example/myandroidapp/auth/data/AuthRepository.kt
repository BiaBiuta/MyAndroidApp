package com.example.myandroidapp.auth.data

import android.util.Log
import com.example.myandroidapp.auth.data.local.AuthProvider
import com.example.myandroidapp.auth.data.local.UserRole
import com.example.myandroidapp.auth.data.remote.AuthDataSource
import com.example.myandroidapp.auth.data.remote.TokenHolder
import com.example.myandroidapp.auth.data.remote.User
import com.example.myandroidapp.core.PasswordHasher
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.data.remote.OfflineException
import com.example.myandroidapp.core.data.remote.ServerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Autentificarea: AICI se decide intre server si baza de date locala, nu in ViewModel.
 *
 *  - Server disponibil: login / inregistrare / Google / Apple / anonim trec prin server, care
 *    da un token JWT; contul (rol, hash-ul parolei) se copiaza in baza locala pentru offline.
 *  - Server indisponibil (fara internet, server oprit): login-ul se verifica in baza locala
 *    (sesiune offline, token "local-..."); inregistrarea unui cont nou NU se poate face offline.
 *
 * ViewModel-ul primeste doar [AuthResult]: sesiunea, sau un mesaj de eroare pentru utilizator.
 */
class AuthRepository(
    private val authDataSource: AuthDataSource,
    private val roleRepository: RoleRepository
) {
    sealed interface AuthResult {
        /** [offline] = serverul nu a raspuns, sesiunea e verificata doar in baza locala. */
        data class Success(val session: UserPreferences, val offline: Boolean) : AuthResult
        data class Failure(
            val message: String,
            val reason: Reason,
            val retryAfterSeconds: Int? = null
        ) : AuthResult
    }

    enum class Reason { INVALID_CREDENTIALS, LOCKED, BLOCKED, OFFLINE, REJECTED }

    /**
     * Login facut offline (server indisponibil): cum ne reautentificam pe server cand revine,
     * ca postarile din coada sa plece fara un nou login. Tinut DOAR in memorie (niciodata pe disc)
     * si sters la primul login online, la logout sau daca serverul refuza datele.
     */
    private sealed interface DeferredLogin {
        class Password(val email: String, val password: CharArray) : DeferredLogin
        class Provider(val provider: String, val email: String) : DeferredLogin
        class Guest(val guestId: String) : DeferredLogin
    }

    @Volatile
    private var deferredLogin: DeferredLogin? = null

    private fun forgetDeferredLogin() {
        (deferredLogin as? DeferredLogin.Password)?.password?.fill('\u0000')
        deferredLogin = null
    }

    /**
     * Sesiune offline + server disponibil din nou: login pe server in fundal, cu datele tinute
     * in memorie. Intoarce sesiunea noua (token JWT real) sau null (nu e cazul / nu se poate inca).
     */
    suspend fun upgradeOfflineSession(): UserPreferences? {
        val token = Api.tokenInterceptor.token
        if (token.isNullOrEmpty() || !token.startsWith("local-")) return null
        val pending = deferredLogin ?: return null
        val result = try {
            when (pending) {
                is DeferredLogin.Password -> {
                    val password = String(pending.password)
                    val hash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
                    onlineSuccess(authDataSource.login(pending.email, password), AuthProvider.EMAIL, hash)
                }
                is DeferredLogin.Provider ->
                    onlineSuccess(authDataSource.signInWithProvider(pending.provider, pending.email), pending.provider, "")
                is DeferredLogin.Guest ->
                    onlineSuccess(authDataSource.signInAnonymously(pending.guestId), AuthProvider.ANONYMOUS, "")
            }
        } catch (e: OfflineException) {
            return null // serverul tot nu raspunde: incercam la urmatoarea sincronizare
        } catch (e: ServerException) {
            // ex. parola schimbata sau cont blocat intre timp: e nevoie de un login nou, manual
            Log.w(TAG, "reautentificarea pe server a esuat: ${e.status}")
            forgetDeferredLogin()
            return null
        }
        forgetDeferredLogin()
        return (result as? AuthResult.Success)?.session
    }

    /** true = sesiunea offline poate trece singura pe server (datele sunt in memorie). */
    fun canUpgradeOfflineSession(): Boolean = deferredLogin != null

    // ---------- E-mail si parola ----------

    suspend fun login(email: String, password: String): AuthResult {
        // hash-ul local (PBKDF2), pentru login-ul offline si pentru sectiunea privata
        val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
        return try {
            onlineSuccess(authDataSource.login(email, password), AuthProvider.EMAIL, passwordHash)
        } catch (e: ServerException) {
            failureFrom(e)
        } catch (e: OfflineException) {
            Log.d(TAG, "server indisponibil, login verificat in baza locala")
            offlineLogin(email, password, passwordHash)
        }
    }

    /** Contul nou se creeaza pe server (sursa de adevar); offline nu se poate. */
    suspend fun register(email: String, password: String): AuthResult {
        val passwordHash = withContext(Dispatchers.Default) { PasswordHasher.hash(password) }
        return try {
            onlineSuccess(authDataSource.signup(email, password), AuthProvider.EMAIL, passwordHash)
        } catch (e: ServerException) {
            failureFrom(e)
        } catch (e: OfflineException) {
            AuthResult.Failure(
                "Crearea unui cont are nevoie de conexiune la server. Încercați din nou când aveți internet.",
                Reason.OFFLINE
            )
        }
    }

    private suspend fun offlineLogin(email: String, password: String, passwordHash: String): AuthResult =
        when (roleRepository.checkOffline(email, password)) {
            RoleRepository.OfflineCheck.VALID -> {
                val account = roleRepository.findAccount(email)
                // inlocuim hash-ul vechi cu unul nou (algoritmul si iteratiile curente)
                if (account != null) roleRepository.saveCredentials(email, account.ownerKey, passwordHash)
                forgetDeferredLogin()
                deferredLogin = DeferredLogin.Password(email, password.toCharArray())
                AuthResult.Success(
                    UserPreferences(
                        email = account?.email ?: email,
                        token = localToken(AuthProvider.EMAIL),
                        // id-ul de pe server, ca postarile contului sa ramana ale lui
                        user = User(email, "", account?.ownerKey?.toIntOrNull() ?: 0),
                        passwordHash = passwordHash,
                        provider = AuthProvider.EMAIL
                    ),
                    offline = true
                )
            }
            // acelasi mesaj pentru cont inexistent si parola gresita (fara user enumeration)
            RoleRepository.OfflineCheck.WRONG_PASSWORD ->
                AuthResult.Failure(INVALID_CREDENTIALS, Reason.INVALID_CREDENTIALS)
            RoleRepository.OfflineCheck.UNKNOWN_ACCOUNT -> AuthResult.Failure(
                "Serverul nu poate fi contactat, iar acest cont nu a mai fost folosit pe telefon. " +
                        "Conectați-vă când aveți internet.",
                Reason.OFFLINE
            )
            RoleRepository.OfflineCheck.BLOCKED ->
                AuthResult.Failure(RoleRepository.BLOCKED_MESSAGE, Reason.BLOCKED)
        }

    // ---------- Google / Apple (simulat) si vizitator anonim ----------

    suspend fun signInWithProvider(provider: String, email: String): AuthResult = try {
        onlineSuccess(authDataSource.signInWithProvider(provider, email), provider, passwordHash = "")
    } catch (e: ServerException) {
        failureFrom(e)
    } catch (e: OfflineException) {
        // offline: doar un cont deja folosit pe acest telefon, cu acelasi furnizor
        val account = roleRepository.findAccount(email)
        when {
            account == null || account.provider != provider -> AuthResult.Failure(
                "Prima conectare cu ${AuthProvider.label(provider)} are nevoie de internet.",
                Reason.OFFLINE
            )
            account.isBlocked -> AuthResult.Failure(RoleRepository.BLOCKED_MESSAGE, Reason.BLOCKED)
            else -> {
                forgetDeferredLogin()
                deferredLogin = DeferredLogin.Provider(provider, email)
                AuthResult.Success(localSession(account.email, account.ownerKey, provider), offline = true)
            }
        }
    }

    suspend fun signInAnonymously(guestId: String): AuthResult = try {
        onlineSuccess(authDataSource.signInAnonymously(guestId), AuthProvider.ANONYMOUS, passwordHash = "")
    } catch (e: ServerException) {
        failureFrom(e)
    } catch (e: OfflineException) {
        // offline, vizitatorul poate vedea postarile salvate pe telefon
        when (val local = roleRepository.signInWithProvider(guestEmail(guestId), AuthProvider.ANONYMOUS)) {
            is RoleRepository.ProviderSignIn.Success -> {
                forgetDeferredLogin()
                deferredLogin = DeferredLogin.Guest(guestId)
                AuthResult.Success(
                    localSession(local.user.email, local.user.ownerKey, AuthProvider.ANONYMOUS),
                    offline = true
                )
            }
            RoleRepository.ProviderSignIn.Blocked ->
                AuthResult.Failure(RoleRepository.BLOCKED_MESSAGE, Reason.BLOCKED)
        }
    }

    // ---------- Logout ----------

    /**
     * Revoca token-ul pe server (daca e disponibil, maxim 3 s) si il sterge din clientul HTTP.
     * Sesiunea salvata pe telefon o sterge MyAppViewModel.
     */
    suspend fun logout() {
        forgetDeferredLogin()
        val token = Api.tokenInterceptor.token
        if (!token.isNullOrEmpty() && !token.startsWith("local-")) {
            withTimeoutOrNull(3_000) {
                try {
                    authDataSource.logout()
                } catch (e: Exception) {
                    Log.w(TAG, "logout pe server esuat (token-ul expira oricum)", e)
                }
            }
        }
        Api.tokenInterceptor.token = null
    }

    // ---------- Ajutatoare ----------

    /** Login reusit pe server: contul ajunge in baza locala (pentru offline) si se creeaza sesiunea. */
    private suspend fun onlineSuccess(holder: TokenHolder, provider: String, passwordHash: String): AuthResult {
        // sesiune reala pe server: datele pentru reautentificare nu mai sunt necesare
        forgetDeferredLogin()
        val email = holder.user.email
        val ownerKey = holder.user.id.toString()
        val sessionProvider = holder.provider ?: provider
        roleRepository.saveCredentials(
            email = email,
            ownerKey = ownerKey,
            passwordHash = passwordHash,
            role = holder.role ?: UserRole.USER,
            provider = sessionProvider
        )
        return AuthResult.Success(
            UserPreferences(
                email = email,
                token = holder.token,
                user = User(email, "", holder.user.id),
                passwordHash = passwordHash,
                provider = sessionProvider
            ),
            offline = false
        )
    }

    private fun localSession(email: String, ownerKey: String, provider: String) = UserPreferences(
        email = email,
        token = localToken(provider),
        user = User(email, "", ownerKey.toIntOrNull() ?: 0),
        provider = provider
    )

    private fun failureFrom(e: ServerException): AuthResult.Failure = when (e.status) {
        401 -> AuthResult.Failure(INVALID_CREDENTIALS, Reason.INVALID_CREDENTIALS)
        403 -> AuthResult.Failure(e.message, Reason.BLOCKED)
        429 -> AuthResult.Failure(e.message, Reason.LOCKED, e.retryAfterSeconds)
        else -> AuthResult.Failure(e.message, Reason.REJECTED)
    }

    companion object {
        /** Acelasi mesaj pentru cont inexistent si parola gresita (fara user enumeration). */
        const val INVALID_CREDENTIALS = "E-mail sau parolă incorecte."

        private fun localToken(provider: String) = "local-" + provider.lowercase() + "-" + UUID.randomUUID()

        /** Aceeasi adresa interna ca pe server: vizitator-<id>@anonim.local */
        fun guestEmail(guestId: String) = "vizitator-${guestId.lowercase()}@anonim.local"
    }
}
