package com.example.myandroidapp

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.core.data.UserPreferences
import com.example.myandroidapp.core.data.UserPreferencesRepository
import com.example.myandroidapp.todo.data.PostRepository
import kotlinx.coroutines.launch

class MyAppViewModel(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val postRepository: PostRepository
) : ViewModel() {

    init {
        Log.d(TAG, "init")
    }
    fun logout(){
        viewModelScope.launch{
            postRepository.deleteAll()
            userPreferencesRepository.save(UserPreferences())//i will set them on null
        }
    }
    fun setToken(token:String){
        postRepository.setToken(token)
    }
    //a static object unique in this class = singleton behavior in java
    //for correct dependency injection
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                MyAppViewModel(
                    app.container.userPreferencesRepository,
                    app.container.postRepository
                )
            }
        }
    }
}
