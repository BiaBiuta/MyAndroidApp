package com.example.myandroidapp.jobs

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Programeaza sincronizarea postarilor din coada (create / editate / sterse offline).
 * WorkManager o porneste doar cand exista retea, o reia la 30 s, 1 min, 2 min, ... (exponential) daca serverul
 * nu raspunde si o pastreaza si dupa inchiderea aplicatiei sau repornirea telefonului.
 * Cat timp aplicatia e deschisa, PostRepository reincearca oricum la fiecare 10 s.
 */
object SyncScheduler {
    private const val WORK_NAME = "post-sync"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // pauze tot mai mari intre incercari (30 s, 1 min, 2 min, ...), ca in propunere
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // o singura sincronizare programata odata
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
