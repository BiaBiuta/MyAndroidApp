package com.example.myandroidapp.jobs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myandroidapp.MyApplication

/** Pornit cand revine reteaua: trimite postarile publice create offline, apoi reincarca lista. */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val itemRepository = (applicationContext as MyApplication).container.postRepository
        return try {
            Log.d("SyncWorker", "unsyncedItems: ${itemRepository.getUnsyncedItems().size}")
            itemRepository.syncUnsynced()
            itemRepository.refresh()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
