package com.example.myandroidapp.todo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Starea de sincronizare a unei postari (insigna de pe fiecare card). */
object SyncStatus {
    /** Modificarea a ajuns pe server. */
    const val SYNCED = "SYNCED"
    /** Salvata doar pe telefon; se trimite automat cand serverul e disponibil. */
    const val PENDING = "PENDING"
    /** Serverul a refuzat-o (ex. date invalide); utilizatorul o deschide, o corecteaza si o salveaza din nou. */
    const val FAILED = "FAILED"
}

/** Starea publicarii: ACTIVE = in feed; DRAFT = ciorna, vizibila doar autorului. */
object PostStatus {
    const val ACTIVE = "ACTIVE"
    const val DRAFT = "DRAFT"
}

@Entity(tableName = "posts")
data class Post(
    // UUID generat pe telefon: aceeasi postare are acelasi id si offline, si pe server
    @PrimaryKey var id: String = "",
    var title: String = "",
    // poza in formatul vechi (Base64 in postare); postarile noi folosesc photoPath / photo_url
    var photo: String = "",
    // poza ca fisier pe telefon (camera / galerie); doar locala, nu se trimite la server
    @field:LocalOnly var photoPath: String = "",
    // adresa pozei pe server ("/photos/<id>"), dupa ce a fost urcata (inaintea postarii)
    var photo_url: String = "",
    // pozele in plus (max. 5): cai locale pana la sincronizare, apoi adrese "/photos/<id>"
    var gallery: List<String> = emptyList(),
    // calatoria (albumul) din care face parte postarea; "" = niciuna
    var trip_id: String = "",
    var description: String = "",
    // 1..5 stele (0 = fara rating, la postarile vechi)
    var rating: Int = 0,
    // data vizitei, in milisecunde (0 = necompletata)
    var visit_date: Long = 0,
    // numele locului (ex. "Cheile Turzii, Cluj"), ales din cautare / GPS / harta (OpenStreetMap)
    var place: String = "",
    // PostStatus: ACTIVE sau DRAFT
    var status: String = PostStatus.ACTIVE,
    // ascunsa de un administrator (o vad doar autorul si adminii)
    var hidden: Boolean = false,
    var user_id: String = "",
    var user_profile_photo: String = "",
    var user_last_name: String = "",
    var user_first_name: String = "",
    var professional: Boolean = false,
    var created_at: String = "",
    // momentul ultimei modificari: decide conflictele ("ultima modificare castiga")
    var updated_at: String = "",
    // "isDirty": are modificari locale care inca nu au ajuns pe server
    var isNotSaved: Boolean = false,
    var location: Location = Location(),
    // Postare privata: se pastreaza doar local, pe telefon, si e vizibila doar
    // proprietarului (user_id), in sectiunea privata, dupa re-autentificare.
    var isPrivate: Boolean = false,
    // ---- doar pe telefon (serverul le ignora) ----
    // SyncStatus: SYNCED / PENDING / FAILED
    var syncStatus: String = SyncStatus.SYNCED,
    // mesajul serverului cand sincronizarea a esuat (FAILED)
    var syncError: String = "",
    // stearsa offline ("tombstone"): ascunsa imediat, stearsa de tot dupa confirmarea serverului
    var isDeleted: Boolean = false,
    // ---- de la server, calculate pentru utilizatorul conectat ----
    var like_count: Int = 0,
    var liked_by_me: Boolean = false,
    var comment_count: Int = 0
) {
    /**
     * Poza de afisat: fisierul local (daca exista inca), altfel cea de pe server, altfel cea veche
     * (Base64). "" = fara poza.
     */
    val displayPhoto: String
        get() = photoPath.takeIf { it.isNotBlank() && java.io.File(it).exists() }
            ?: photo_url.ifBlank { photo }

    /** Toate pozele postarii, in ordine: cea principala, apoi galeria (doar cele care exista). */
    val allPhotos: List<String>
        get() = listOf(displayPhoto).filter { it.isNotBlank() } +
                gallery.filter { it.startsWith("/photos/") || it.startsWith("data:") || java.io.File(it).exists() }

    /** Pozele din formular: prima devine poza principala, restul galeria. */
    fun withPhotos(photos: List<String>): Post =
        withPhoto(photos.firstOrNull().orEmpty()).copy(gallery = photos.drop(1).take(5))

    /** Aceeasi postare cu alta poza (valoarea vine din formular: fisier local, adresa server sau Base64). */
    fun withPhoto(value: String): Post = when {
        value == displayPhoto -> this
        value.isBlank() -> copy(photo = "", photoPath = "", photo_url = "")
        value.startsWith("/photos/") -> copy(photo = "", photoPath = "", photo_url = value)
        value.startsWith("data:") -> copy(photo = value, photoPath = "", photo_url = "")
        else -> copy(photo = "", photoPath = value, photo_url = "")
    }
}
