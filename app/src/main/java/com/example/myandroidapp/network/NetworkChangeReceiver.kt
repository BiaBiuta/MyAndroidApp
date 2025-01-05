package com.example.myandroidapp.network;
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.example.myandroidapp.core.TAG
import com.example.myandroidapp.jobs.SyncWorker
import kotlin.math.log

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (isOnline(context)) {
            Log.d(TAG,"sunt online")
            // Pornește un worker pentru a sincroniza datele salvate local
            val syncWork = OneTimeWorkRequest.Builder(SyncWorker::class.java).build()
            WorkManager.getInstance(context).enqueue(syncWork)
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
