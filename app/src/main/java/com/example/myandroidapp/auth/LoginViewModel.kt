package com.example.myandroidapp.auth

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.auth.data.AuthRepository
import com.example.myandroidapp.auth.data.remote.User
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.UserPreferencesRepository
import kotlinx.coroutines.launch

//first of all - define LoginUiState
//second - define class LoginViewModel
//      -takes repositoryAuth cat si repositoryUserPreferences
//define a component  type uiState - cu mutableStateOf
//log for enter in init
//function login
//          -use viewModelScope.launch to not blocking the ui
//          -change the state in isAuthenticating
//          -appeal function of  login from authRepository
//          -change the state sin authenticating complete
//          -else set the error state
//third- it create a static object for describe how to initialized the model
data class LoginUiState(
    val isAuthenticatingProgress:Boolean=false,
    val isAuthenticate:Boolean=false,
    val authenticationError:Throwable?=null,
    val token:String=""
)
class LoginViewModel(
       private val authRepository: AuthRepository,
       private val userPreferencesRepository: UserPreferencesRepository
    ): ViewModel(){
    var uiState: LoginUiState by mutableStateOf(LoginUiState())
    init {
        Log.d(TAG,"init")
    }
    fun login(email:String , password :String){
        viewModelScope.launch{
            Log.v(TAG,"login...")
            uiState=uiState.copy(isAuthenticatingProgress = true, authenticationError = null)
            val result =authRepository.login(email,password)
            if(result.isSuccess){
                userPreferencesRepository.save(
                    UserPreferences(
                        email,
                        result.getOrNull()?.token?:"",
                        result.getOrNull()?.user?: User("","",0)
                    )
                )
                Log.d(TAG,result.getOrNull()?.user?.id.toString()?:"")
                uiState=uiState.copy(isAuthenticatingProgress = false,isAuthenticate = true)
            }
            else{
                uiState=uiState.copy(
                    isAuthenticatingProgress = false,
                    isAuthenticate = false,
                    authenticationError = result.exceptionOrNull()//return throw error encapsulated
                )
            }
        }
    }
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                //cum se initializeaza viewModelul in aplicatie
                //contine o referinta la aplicatia Android folosind cheia
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                LoginViewModel(
                    app.container.authRepository,
                    app.container.userPreferencesRepository
                )
            }
        }
    }
}
