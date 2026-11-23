package com.example.myapplication.core

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import com.example.myandroidapp.auth.data.AuthRepository
import com.example.myandroidapp.auth.data.RoleRepository
import com.example.myandroidapp.auth.data.remote.AuthDataSource
import com.example.myandroidapp.auth.data.remote.UserService
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.UserPreferencesRepository
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.remote.PostService
import com.example.myandroidapp.todo.data.remote.PostWsClient
import com.example.myapp.MyAppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

//import com.example.myapplication.MyAppDatabase
//import com.example.myapplication.auth.data.AuthRepository
//import com.example.myapplication.auth.data.remote.AuthDataSource
//import com.example.myapplication.core.data.UserPreferencesRepository
//import com.example.myapplication.core.data.remote.Api
//import com.example.myapplication.todo.data.ItemRepository
//import com.example.myapplication.todo.data.remote.ItemService
//import com.example.myapplication.todo.data.remote.ItemWsClient

val Context.userPreferencesDataStore by preferencesDataStore(
    name = "user_preferences"
)

class AppContainer(val context: Context) {
    init {
        Log.d(TAG, "init")
    }
    //it implement in a dynamic mode methods from interface
    private val postService: PostService = Api.retrofit.create(PostService::class.java)
    // ping la 15 s: o conexiune "moarta" (server oprit, cablu / Wi-Fi intrerupt) e detectata
    // repede si socket-ul se reconecteaza, in loc sa para deschis la nesfarsit
    private val postWsClient: PostWsClient = PostWsClient(
        Api.okHttpClient.newBuilder().pingInterval(15, java.util.concurrent.TimeUnit.SECONDS).build()
    )
    private val authDataSource: AuthDataSource = AuthDataSource()
    private val database: MyAppDatabase by lazy { MyAppDatabase.getDatabase(context) }
    //private val database: MyAppDatabase by lazy { MyAppDatabase.getDatabase(context) }
    //*
    // we want to access from all models this repository
    //it will be late and it will appeal once at the first
    //initialization
    //*
    val postRepository: PostRepository by lazy {
        PostRepository(
            postService,
            postWsClient,
            database.itemDao(),
            database.moderationDao(),
            currentOwnerKey = { userPreferencesRepository.userPreferencesStream.first().ownerKey },
            ownerKeys = userPreferencesRepository.userPreferencesStream.map { it.ownerKey },
            ensureServerSession = { upgradeOfflineSession() },
            scheduleSync = { com.example.myandroidapp.jobs.SyncScheduler.schedule(context) }
        )
    }

    // Trackerele WanderLog (IoT): asociere cu cod, chei per dispozitiv, actualizari semnate
    val deviceRepository: com.example.myandroidapp.iot.data.DeviceRepository by lazy {
        com.example.myandroidapp.iot.data.DeviceRepository(
            service = Api.retrofit.create(com.example.myandroidapp.iot.data.remote.DeviceService::class.java),
            dao = database.pairedDeviceDao(),
            tracker = com.example.myandroidapp.iot.data.TrackerClient(),
            ownerKeys = userPreferencesRepository.userPreferencesStream.map { it.ownerKey },
            currentOwnerKey = { userPreferencesRepository.userPreferencesStream.first().ownerKey }
        )
    }

    /**
     * Login-ul a fost facut offline, iar serverul raspunde din nou: sesiunea trece pe server in
     * fundal (token JWT real), pastrand alegerea pentru amprenta. Apelat inainte de sincronizare.
     */
    private suspend fun upgradeOfflineSession(): Boolean {
        val upgraded = authRepository.upgradeOfflineSession() ?: return false
        val current = userPreferencesRepository.userPreferencesStream.first()
        // alt cont conectat intre timp: nu amestecam sesiunile
        if (current.email.isNotEmpty() && !current.email.equals(upgraded.email, ignoreCase = true)) return false
        userPreferencesRepository.save(
            UserPreferences(
                email = upgraded.email,
                token = upgraded.token,
                user = upgraded.user,
                biometricEnabled = current.biometricEnabled,
                passwordHash = upgraded.passwordHash.ifEmpty { current.passwordHash },
                provider = upgraded.provider
            )
        )
        Api.tokenInterceptor.token = upgraded.token
        postRepository.setToken(upgraded.token)
        return true
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(authDataSource, roleRepository)
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context.userPreferencesDataStore)
    }

    // Rolurile (USER / ADMIN) ale conturilor, in baza de date locala
    val roleRepository: RoleRepository by lazy {
        RoleRepository(
            database.userDao(),
            userPreferencesRepository,
            Api.retrofit.create(UserService::class.java)
        )
    }
}
