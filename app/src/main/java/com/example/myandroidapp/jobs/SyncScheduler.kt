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
 * WorkManager o porneste doar cand exista retea, o reia cu pauze tot mai mari daca serverul
 * nu raspunde (30 s, 1 min, 2 min, ...) si o pastreaza si dupa inchiderea aplicatiei sau repornirea telefonului.
 */
object SyncScheduler {
    private const val WORK_NAME = "post-sync"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // o singura sincronizare programata odata
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
