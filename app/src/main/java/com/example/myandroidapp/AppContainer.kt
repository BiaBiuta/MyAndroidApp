package com.example.myapplication.core

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import com.example.myandroidapp.auth.data.AuthRepository
import com.example.myandroidapp.auth.data.remote.AuthDataSource
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferencesRepository
import com.example.myandroidapp.todo.data.PostRepository
import com.example.myandroidapp.todo.data.remote.PostService
import com.example.myandroidapp.todo.data.remote.PostWsClient
import com.example.myapp.MyAppDatabase

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
    private val postWsClient: PostWsClient = PostWsClient(Api.okHttpClient)
    private val authDataSource: AuthDataSource = AuthDataSource()
    private val database: MyAppDatabase by lazy { MyAppDatabase.getDatabase(context) }
    //private val database: MyAppDatabase by lazy { MyAppDatabase.getDatabase(context) }
    //*
    // we want to access from all models this repository
    //it will be late and it will appeal once at the first
    //initialization
    //*
    val postRepository: PostRepository by lazy {
        PostRepository(postService, postWsClient,database.itemDao())
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(authDataSource)
    }

    val userPreferencesRepository: UserPreferencesRepository by lazy {
        UserPreferencesRepository(context.userPreferencesDataStore)
    }
}
