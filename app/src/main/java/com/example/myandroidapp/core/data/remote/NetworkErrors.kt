package com.example.myandroidapp.core.data.remote

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Serverul a raspuns, dar cu o eroare (4xx / 5xx). [message] e mesajul trimis de server
 * (in romana, pentru utilizator), sau unul generic daca serverul nu a trimis niciunul.
 */
class ServerException(
    val status: Int,
    override val message: String,
    val retryAfterSeconds: Int? = null,
    // corpul JSON al raspunsului (ex. la 409 contine versiunea de pe server a postarii)
    val body: String? = null
) : Exception(message)

/** Serverul nu a putut fi contactat (fara internet, server oprit, adresa gresita, timeout). */
class OfflineException(cause: Throwable? = null) :
    IOException("Serverul nu poate fi contactat.", cause)

/**
 * Executa un apel Retrofit si transforma erorile in [ServerException] / [OfflineException],
 * ca repository-urile sa poata decide simplu: "server indisponibil -> folosesc datele locale".
 */
suspend fun <T> apiCall(block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    throw e.toServerException()
} catch (e: IOException) {
    throw if (e is OfflineException) e else OfflineException(e)
}

private fun HttpException.toServerException(): ServerException {
    val body = try {
        response()?.errorBody()?.string()
    } catch (e: Exception) {
        null
    }
    val serverMessage = try {
        body?.let { JsonParser().parse(it).asJsonObject.get("message")?.asString }
    } catch (e: Exception) {
        null
    }
    val retryAfter = response()?.headers()?.get("Retry-After")?.toIntOrNull()
    return ServerException(code(), serverMessage ?: defaultMessage(code()), retryAfter, body)
}

private fun defaultMessage(status: Int) = when (status) {
    400 -> "Datele trimise nu sunt valide."
    401 -> "Sesiunea a expirat. Conectați-vă din nou."
    403 -> "Nu aveți dreptul să faceți această operație."
    404 -> "Informația cerută nu mai există pe server."
    409 -> "Operația intră în conflict cu datele de pe server."
    413 -> "Datele trimise sunt prea mari (de exemplu, o poză prea mare)."
    429 -> "Prea multe încercări. Așteptați puțin și încercați din nou."
    in 500..599 -> "Serverul are o problemă momentan. Încercați din nou mai târziu."
    else -> "Serverul a răspuns cu o eroare neașteptată."
}

/** Mesaj prietenos (fara termeni tehnici) pentru orice eroare, afisat in aplicatie. */
fun Throwable.toUserMessage(fallback: String = "A apărut o eroare. Încercați din nou."): String = when (this) {
    is ServerException -> message
    is OfflineException -> cause?.toUserMessage(fallback)
        ?: "Serverul nu poate fi contactat. Verificați conexiunea la internet."
    is UnknownHostException, is ConnectException, is NoRouteToHostException ->
        "Serverul nu poate fi contactat. Verificați conexiunea la internet."
    is SocketTimeoutException -> "Serverul răspunde prea greu. Încercați din nou."
    is IOException -> "Problemă de conexiune. Încercați din nou."
    else -> fallback
}
