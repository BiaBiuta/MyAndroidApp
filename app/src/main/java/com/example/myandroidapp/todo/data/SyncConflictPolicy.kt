package com.example.myandroidapp.todo.data

/**
 * Ce face sincronizarea cand serverul refuza o postare trimisa din coada (vezi propunerea,
 * "How are conflicts resolved?" si "What happens if synchronization fails?").
 * Separat de PostRepository ca sa poata fi verificat cu teste unitare (SyncConflictPolicyTest).
 */
object SyncConflictPolicy {

    enum class Outcome {
        /** Ramane PENDING si se reincearca automat (WorkManager, backoff exponential). */
        RETRY_LATER,
        /** 409: altcineva / alt dispozitiv a modificat-o mai recent -> castiga versiunea serverului. */
        KEEP_SERVER_VERSION,
        /** 410 la o editare: moderatorul a sters-o -> modificarile devin ciorna privata (nu se pierd). */
        SAVE_AS_PRIVATE_DRAFT,
        /** 404 / 410 la o stergere: postarea nu mai exista pe server -> stergerea e deja facuta. */
        ALREADY_DELETED,
        /** 400 / 403 / 413 / 422: date invalide -> FAILED, "Sincronizare eșuată – atinge pentru a corecta". */
        FAILED
    }

    fun onServerError(status: Int, deletedLocally: Boolean): Outcome = when {
        // sesiune expirata, server ocupat sau cu probleme: nimic nu e gresit la postare
        status == 401 || status == 408 || status == 429 || status in 500..599 -> Outcome.RETRY_LATER
        deletedLocally && (status == 404 || status == 410) -> Outcome.ALREADY_DELETED
        status == 409 -> Outcome.KEEP_SERVER_VERSION
        status == 410 -> Outcome.SAVE_AS_PRIVATE_DRAFT
        else -> Outcome.FAILED
    }

    /** Titlul folosit in mesaje: titlul, altfel inceputul descrierii. */
    fun displayTitle(title: String, description: String): String =
        title.trim().ifBlank { description.trim().take(40) }.ifBlank { "Postarea" }

    fun keptServerVersionMessage(title: String): String =
        "„$title” a fost modificată pe alt dispozitiv. S-a păstrat varianta mai nouă."

    fun savedAsDraftMessage(title: String): String =
        "„$title” a fost ștearsă de un moderator. Modificările tale au fost salvate ca ciornă în secțiunea privată."
}
