package com.example.myandroidapp.auth.data

import android.util.Log
import com.example.myandroidapp.auth.data.remote.AuthDataSource
import com.example.myandroidapp.auth.data.remote.TokenHolder
import com.example.myandroidapp.auth.data.remote.User
import com.example.myandroidapp.core.data.remote.Api
import com.example.myandroidapp.core.TAG

class AuthRepository (private val authDataSource: AuthDataSource){
    init {
        Log.d(TAG, "init")
    }
    fun clearToken(){
        Api.tokenInterceptor.token=null
    }
    //auth repository , are un authDataSource
    //authDataSource- > apeleaza pe un user methoda login
    //login va returna un result , care va contine si un obiect tokenInterceptor
    //pe care il vom set aici
    //daca result e cu succes
    suspend fun login(email:String,password:String):Result<TokenHolder>{
        val user= User(email,password)
        val result=authDataSource.login(user)
        if(result.isSuccess){
            Api.tokenInterceptor.token=result.getOrNull()?.token
        }
        return result
    }
}