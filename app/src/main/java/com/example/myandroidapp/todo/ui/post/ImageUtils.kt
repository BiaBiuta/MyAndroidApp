//import android.content.ContentResolver
//import android.net.Uri
//import android.util.Base64
//import android.util.Log
//import java.io.File
//import java.io.FileInputStream
//import java.io.IOException
//import java.io.InputStream
//import kotlinx.coroutines.*
//
//
//import kotlin.math.log
//
//object ImageUtils {
//    // Funcție pentru a citi o imagine dintr-un fișier și a o transforma în base64
//     suspend fun convertImageToBase64(imageUri: Uri): String? {
//        return try {
//            val contentResolver: ContentResolver = contentResolver
//            val inputStream: InputStream? = contentResolver.openInputStream(imageUri)
//            val bytes = inputStream?.readBytes()
//            inputStream?.close()
//
//            if (bytes != null) {
//                // Convertește byte array într-un șir base64
//                "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.DEFAULT)
//            } else {
//                null
//            }
//        } catch (e: IOException) {
//            e.printStackTrace()
//            null
//        }
//}