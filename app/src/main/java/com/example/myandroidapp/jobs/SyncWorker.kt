package com.example.myandroidapp.jobs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myandroidapp.MyApplication
import com.example.myandroidapp.todo.data.Post

class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val itemRepository = (applicationContext as MyApplication).container.postRepository
        return try {
            // Obține toate itemele nesincronizate din baza de date locală
            val unsyncedItems = itemRepository.getUnsyncedItems() // Implementare în DAO
            Log.d("SyncWorker", "unsyncedItems: ${unsyncedItems.size}")
            // Sincronizează fiecare item
            unsyncedItems.forEach { item ->
                itemRepository.save(item) // Trimite datele către server
                itemRepository.markAsSynced(item.id)
               // Marchează item-ul ca sincronizat
            }
            itemRepository.refresh()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
