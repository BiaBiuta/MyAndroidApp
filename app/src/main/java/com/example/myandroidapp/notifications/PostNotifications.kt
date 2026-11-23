package com.example.myandroidapp.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.myandroidapp.R
import com.example.myandroidapp.todo.data.Post

/**
 * Notificarile aplicatiei:
 *  - "Postări noi": altcineva a publicat o postare (eveniment WebSocket);
 *  - "Sincronizare": postarile salvate offline au ajuns pe server.
 * Atingerea notificarii deschide aplicatia. Fara permisiunea de notificari (Android 13+) nu se
 * afiseaza nimic (permisiunea e ceruta din ecranul principal).
 */
class PostNotifications(context: Context) {
    private val context = context.applicationContext

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = this.context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_POSTS, "Postări noi", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Când alți călători publică postări" }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SYNC, "Sincronizare", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Când postările salvate offline ajung pe server" }
            )
        }
    }

    fun newPostFromOthers(post: Post) {
        val author = post.user_first_name.ifBlank { "Un călător" }
        val what = post.title.ifBlank { post.place }.ifBlank { post.description.take(60) }.ifBlank { "o postare nouă" }
        val text = buildString {
            append(what)
            if (post.place.isNotBlank() && post.place != what) append(" · ").append(post.place)
            if (post.rating > 0) append(" · ").append("★".repeat(post.rating))
        }
        show(CHANNEL_POSTS, post.id.hashCode(), "$author a publicat ceva nou", text)
    }

    fun offlinePostsSynced(count: Int) {
        if (count <= 0) return
        val text = if (count == 1) "Postarea salvată offline a fost publicată."
        else "$count postări salvate offline au fost publicate."
        show(CHANNEL_SYNC, SYNC_NOTIFICATION_ID, "Sincronizare reușită", text)
    }

    private fun show(channel: String, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = open?.let {
            PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // permisiunea a fost retrasa intre timp
        }
    }

    companion object {
        const val CHANNEL_POSTS = "wanderlog-posts"
        const val CHANNEL_SYNC = "wanderlog-sync"
        private const val SYNC_NOTIFICATION_ID = 4201
    }
}
