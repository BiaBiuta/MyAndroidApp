package com.example.myandroidapp.core.ui

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.myandroidapp.core.data.remote.Api
import java.io.File

/**
 * Incarcarea pozelor postarilor (Coil):
 *  - fisierele locale (pozele facute / alese pe acest telefon) se citesc direct;
 *  - cele de pe server ("/photos/<id>") se descarca prin clientul HTTP al aplicatiei, deci cu
 *    token-ul JWT (serverul nu da pozele fara autentificare), si raman in cache pe disc;
 *  - offline se afiseaza din cache, fara sa mai intrebe serverul.
 */
object PhotoLoader {
    @Volatile
    private var loader: ImageLoader? = null

    fun get(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .okHttpClient(Api.okHttpClient)
            // pozele nu se schimba (fiecare are id propriu): din cache chiar si fara retea
            .respectCacheHeaders(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.applicationContext.cacheDir, "post-photos"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizePercent(0.2).build() }
            .crossfade(true)
            .build()
            .also { loader = it }
    }

    /** Ce primeste Coil: adresa completa pentru pozele de pe server, fisierul pentru cele locale. */
    fun model(photo: String): Any = if (photo.startsWith("/photos/")) Api.absoluteUrl(photo) else File(photo)
}
