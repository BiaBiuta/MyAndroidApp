package com.example.myandroidapp.todo.ui.post

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Pregateste poza aleasa din galerie pentru o postare: o micsoreaza (latura mare de cel mult
 * [MAX_DIMENSION] px) si o comprima JPEG pana sub [MAX_BYTES], apoi o transforma in Base64.
 *
 * De ce: poza ajunge in baza de date locala (Room / SQLite) si pe server. O poza de la camera
 * are cativa MB, iar SQLite pe Android nu poate citi un rand mai mare de ~2 MB
 * ("Row too big to fit into CursorWindow") -> aplicatia s-ar opri la afisarea listei.
 */
object PhotoEncoder {
    private const val MAX_DIMENSION = 1280
    private const val MAX_BYTES = 350 * 1024
    private const val TAG = "PhotoEncoder"

    /** Base64 cu prefixul "data:image/jpeg;base64,", sau null daca poza nu poate fi citita. */
    fun encode(contentResolver: ContentResolver, uri: Uri): String? = try {
        val bitmap = decodeScaled(contentResolver, uri)
        if (bitmap == null) {
            null
        } else {
            val bytes = compress(bitmap)
            bitmap.recycle()
            Log.d(TAG, "poza pregatita: ${bytes.size / 1024} KB")
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        Log.w(TAG, "poza nu a putut fi citita", e)
        null
    }

    private fun decodeScaled(contentResolver: ContentResolver, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder aplica si rotatia din EXIF (pozele facute vertical nu ies culcate)
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { decoder, info, _ ->
                val (width, height) = info.size.width to info.size.height
                val scale = MAX_DIMENSION.toFloat() / maxOf(width, height)
                if (scale < 1f) decoder.setTargetSize((width * scale).toInt(), (height * scale).toInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // Android 7-8: citim intai doar dimensiunile, apoi poza deja micsorata (memorie putina)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?.let { scaleDown(it) }
        }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val scale = MAX_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        val scaled = Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    /** JPEG din ce in ce mai comprimat, pana incape in [MAX_BYTES]. */
    private fun compress(bitmap: Bitmap): ByteArray {
        var quality = 85
        while (true) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (output.size() <= MAX_BYTES || quality <= 40) return output.toByteArray()
            quality -= 10
        }
    }
}
