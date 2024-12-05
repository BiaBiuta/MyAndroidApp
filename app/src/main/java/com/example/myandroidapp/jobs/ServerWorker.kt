package com.example.myandroidapp.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.todo.data.Location
import com.example.myandroidapp.todo.data.Post


class ServerWorker(
    context: Context,
    val workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val itemRepository =(applicationContext as MyApplication).container.postRepository;

        val isSaving = workerParams.inputData.getBoolean("isSaving", true);
        val id = workerParams.inputData.getString("id")!!
        val photo = workerParams.inputData.getString("photo")!!
        var user_id=workerParams.inputData.getString("user_id")!!



        val isNotSaved: Boolean = workerParams.inputData.getBoolean("isNotSaved",false) // Aici, false este valoarea implicită

        //var location=workerParams.inputData.getString("location")!!
        val description = workerParams.inputData.getString("description")!!



        val item = Post(
            id = id,
            photo = photo,
            user_id = user_id,
            user_profile_photo = "",
            user_last_name = "",
            user_first_name = "",
            professional = false,
            created_at = "",
            updated_at = "",
            isNotSaved = isNotSaved,
            location = Location(0.0,0.0),
            description = description
        )

        if(isSaving) {
            //itemRepository.deleteItem(item)
            itemRepository.save(item)
        } else {
            itemRepository.update(item)
        }

        return Result.success()
    }
}