package com.example.myandroidapp.jobs

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
        val photoData = File(photoPath).readText()

        var user_id = workerParams.inputData.getString("user_id")!!


        val isNotSaved: Boolean = workerParams.inputData.getBoolean(
            "isNotSaved",
            false
        ) // Aici, false este valoarea implicită

        //var location=workerParams.inputData.getString("location")!!
        val description = workerParams.inputData.getString("description")!!


        val item = Post(
            id = id,
            photo = photoData,
            user_id = user_id,
            user_profile_photo = "",
            user_last_name = "",
            user_first_name = "",
            professional = false,
            created_at = "",
            updated_at = "",
            isNotSaved = true,
            location = Location(0.0, 0.0),
            description = description
        )

        return if (isOnline(applicationContext)) {
            // Trimite datele către server
            try {
                if (isSaving) {
                    itemRepository.save(item) // Salvare pe server
                } else {
                    itemRepository.update(item) // Actualizare pe server
                }
                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()
                Result.retry() // Reîncearcă dacă apare o eroare
            }
        } else {
            // Salvează datele local
            try {
                itemRepository.saveToLocal(item) // Asigură-te că `saveToLocal` este implementat în DAO
                Result.success()
            } catch (e: Exception) {
                e.printStackTrace()
                Result.failure()
            }
        }
    }
    private fun isOnline(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}