package com.example.myandroidapp.todo.ui.post

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.material3.DatePicker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.todo.data.Post
import com.example.myandroidapp.todo.data.PostRepository
import kotlinx.coroutines.launch
import com.example.myandroidapp.core.Result
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.todo.data.Location

data class PostUiState(
    val postId:String?=null,
    val post :Post = Post(),
    var loadResult:Result<Post>?=null,
    var submitResult:Result<Post>?=null
)
class PostViewModel(private val postId: String?, private val itemRepository: PostRepository) :
    ViewModel() {

    var uiState: PostUiState by mutableStateOf(PostUiState(loadResult = Result.Loading))
        private set

    init {
        Log.d(TAG, "init")
        if (postId != null) {
            loadItem()
        } else {
            uiState = uiState.copy(loadResult = Result.Success(Post()))
        }
    }

    fun loadItem() {
        viewModelScope.launch {
            itemRepository.itemStream.collect { result ->
                if (!(uiState.loadResult is Result.Loading)) {
                    return@collect
                }
                if(result is Result.Success){
                    val posts=result.data
                    val post =posts.find { it.id==postId }?:Post()
                    uiState = uiState.copy(post = post, loadResult = Result.Success(post))

                }
                //val item = items.find { it.id == itemId } ?: Post()
            }
        }
    }



    @RequiresApi(Build.VERSION_CODES.O)
    fun saveOrUpdateItem(photo:String, description:String, user_id:String, user_profile_photo:String, user_last_name:String, user_first_name:String, selectedLocation: Location) {

        viewModelScope.launch {
            Log.d(TAG, "saveOrUpdateItem...");
            try {
                uiState = uiState.copy(submitResult = Result.Loading)
                val createdAt = java.util.Date().toInstant().toString() // Echivalent pentru new Date().toISOString() în JavaScript
                val updatedAt = java.util.Date().toInstant().toString() // Data curentă pentru updated_at

                val location = Location(selectedLocation.latitude,selectedLocation.longitude)
                   ?: Location( 47.0,  48.0)
                val postId=uiState.postId
                val item = uiState.post.copy(
                     photo=photo?:"",
                 description=description?:"",
                 user_id=user_id?:"",
                 user_profile_photo=user_profile_photo?:"",
                 user_last_name=user_last_name?:"",
                 user_first_name=user_first_name?:"",
                 professional=false,
                 created_at= createdAt,  // Consistent cu `updated_at`
                 updated_at=updatedAt,
                 //isNotSaved: Boolean=false,
                 location=location 
                )
                val editPost=item.copy(id=postId?:"",createdAt)
                val savedItem: Post;
                if (postId == null) {
                    savedItem = itemRepository.save(item)
                } else {
                    savedItem = itemRepository.update(editPost)
                }
                Log.d(TAG, "saveOrUpdateItem succeeeded");
                uiState = uiState.copy(submitResult = Result.Success(savedItem))
            } catch (e: Exception) {
                Log.d(TAG, "saveOrUpdateItem failed");
                uiState = uiState.copy(submitResult = Result.Error(e))
            }
        }
    }

    companion object {
        fun Factory(itemId: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app =
                    (this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as MyApplication)
                PostViewModel(itemId, app.container.postRepository)
            }
        }
    }
}
