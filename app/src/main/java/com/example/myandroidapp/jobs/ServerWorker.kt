package com.example.myandroidapp.jobs

import android.util.Log
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.data.Post
import java.io.File


class ServerWorker(
    context: Context,
    val workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val itemRepository = (applicationContext as MyApplication).container.postRepository;

        val isSaving = workerParams.inputData.getBoolean("isSaving", true);
        val id = workerParams.inputData.getString("id")!!
        val photoPath = workerParams.inputData.getString("photoPath")!!
        val latitudine = workerParams.inputData.getDouble("latitudine",0.0)!!
        val longitudine = workerParams.inputData.getDouble("longitudine",0.0)!!
        val photoData = File(photoPath).readText()

        var user_id = workerParams.inputData.getString("user_id")!!


        val isNotSaved: Boolean = workerParams.inputData.getBoolean(
            "isNotSaved",
            false
        ) // Aici, false este valoarea implicită

        //var location=workerParams.inputData.getString("location")!!
        val description = workerParams.inputData.getString("description")!!


        var item = Post(
            id = id,
            photo = photoData,
            user_id = user_id,
            user_profile_photo = "",
            user_last_name = "",
            user_first_name = workerParams.inputData.getString("user_first_name").orEmpty(),
            professional = false,
            created_at = "",
            updated_at = "",
            isNotSaved = true,
            location = Location(latitudine, longitudine),
            description = description
        )

        // Repository-ul decide: trimite pe server sau, daca serverul nu raspunde, pune in coada
        return try {
            if (isSaving) {
                val result = itemRepository.createPost(item.copy(isNotSaved = false))
                Log.d("ServerWorker", "postare noua: $result")
            } else {
                itemRepository.updatePost(item)
            }
            Result.success()
        } catch (e: Exception) {
            // ex. serverul a refuzat postarea (date invalide, cont fara drept de publicare)
            Log.w("ServerWorker", "postarea nu a putut fi salvata", e)
            Result.failure()
        }
    }
}