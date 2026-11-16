package com.example.myandroidapp.jobs

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.myandroidapp.MyApplication

/**
 * Pornit cand revine reteaua (SyncScheduler / NetworkChangeReceiver): trimite pe server tot ce s-a
 * modificat offline, apoi reincarca lista. Daca serverul tot nu raspunde, WorkManager reincearca.
 */
class SyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as MyApplication).container.postRepository
        return try {
            val done = repository.syncUnsynced()
            repository.refresh()
            Log.d("SyncWorker", "sincronizare ${if (done) "completa" else "incompleta, se reia"}")
            if (done) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.w("SyncWorker", "sincronizarea a esuat, se reia", e)
            Result.retry()
        }
    }
}
