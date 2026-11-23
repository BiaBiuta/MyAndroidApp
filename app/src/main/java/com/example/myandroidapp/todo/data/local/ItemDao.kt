package com.example.myapp.todo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.myandroidapp.todo.data.Post

import kotlinx.coroutines.flow.Flow

@Dao
abstract class ItemDao {
    // Lista principala: DOAR postarile publice. Postarile private nu apar aici niciodata.
    // Postarile sterse (offline = isDeleted, sau confirmat = hidden_posts) nu mai apar.
    @Query(
        "SELECT * FROM Posts WHERE isPrivate = 0 AND isDeleted = 0 " +
                "AND id NOT IN (SELECT postId FROM hidden_posts) ORDER BY created_at DESC"
    )
    abstract fun getAll(): Flow<List<Post>>

    // ===== NOU =====
    // Lista principala pentru contul conectat (ownerKey): postarile NESINCRONIZATE
    // (create / editate offline) le vede DOAR autorul lor; ceilalti le vad dupa sincronizare.
    @Query(
        "SELECT * FROM Posts WHERE isPrivate = 0 AND isDeleted = 0 " +
                "AND id NOT IN (SELECT postId FROM hidden_posts) " +
                "AND (user_id = :ownerKey OR (isNotSaved = 0 AND syncStatus = 'SYNCED')) " +
                "ORDER BY created_at DESC"
    )
    abstract fun getVisibleTo(ownerKey: String): Flow<List<Post>>

    // "Postarile mele" (rol USER): postarile publice ale unui utilizator
    @Query(
        "SELECT * FROM Posts WHERE isPrivate = 0 AND isDeleted = 0 AND user_id = :ownerKey " +
                "AND id NOT IN (SELECT postId FROM hidden_posts) ORDER BY created_at DESC"
    )
    abstract fun getPublicByOwner(ownerKey: String): Flow<List<Post>>

    // Sectiunea privata: doar postarile private ale proprietarului dat.
    @Query("SELECT * FROM Posts WHERE isPrivate = 1 AND user_id = :ownerKey ORDER BY created_at DESC")
    abstract fun getPrivate(ownerKey: String): Flow<List<Post>>

    // Editare: postarea dupa id
    @Query("SELECT * FROM Posts WHERE id = :id")
    abstract suspend fun findById(id: String): Post?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(item: Post)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insert(items: List<Post>)

    @Update
    abstract suspend fun update(item: Post): Int

    // Sterge doar postarile publice (cele venite de pe server). Cele private raman,
    // ca utilizatorul sa le regaseasca dupa ce se logheaza din nou.
    @Query("DELETE FROM Posts WHERE isPrivate = 0")
    abstract suspend fun deleteAllPublic(): Unit

    // La refresh: inlocuim postarile publice venite de pe server, dar NU pe cele
    // create pe telefon si inca netrimise (isNotSaved = 1), ca sa nu se piarda.
    @Query("DELETE FROM Posts WHERE isPrivate = 0 AND isNotSaved = 0")
    abstract suspend fun deleteAllPublicSynced(): Unit

    // Refresh de pe server intr-o singura tranzactie: lista din UI nu apare goala intre pasi
    @Transaction
    open suspend fun replacePublicSynced(posts: List<Post>) {
        deleteAllPublicSynced()
        insert(posts)
    }

    // Postarile din coada (create offline, inca netrimise) - pentru bannerul offline
    @Query("SELECT COUNT(*) FROM Posts WHERE isNotSaved = 1 AND isPrivate = 0 AND syncStatus = 'PENDING'")
    abstract fun observeUnsyncedCount(): Flow<Int>

    // ===== NOU =====
    // Acelasi numar, dar doar pentru contul conectat (nu numara postarile altui cont)
    @Query("SELECT COUNT(*) FROM Posts WHERE isNotSaved = 1 AND isPrivate = 0 AND syncStatus = 'PENDING' AND user_id = :ownerKey")
    abstract fun observeUnsyncedCountFor(ownerKey: String): Flow<Int>

    @Query("DELETE FROM Posts WHERE id = :id")
    abstract suspend fun deleteById(id: String): Unit

    // Postarile private nu se trimit niciodata pe server
    // Coada de sincronizare: create / editate / sterse offline (FAILED asteapta corectarea)
    @Query("SELECT * FROM Posts WHERE isNotSaved = 1 AND isPrivate = 0 AND syncStatus = 'PENDING' ORDER BY updated_at ASC")
    abstract suspend fun getUnsyncedItems(): List<Post>

    @Query("UPDATE Posts SET isNotSaved = 0 WHERE id = :id")
    abstract suspend fun markAsSynced(id: String)

    // id-urile postarilor cu modificari locale netrimise: refresh-ul nu le suprascrie
    @Query("SELECT id FROM Posts WHERE isNotSaved = 1")
    abstract suspend fun getDirtyIds(): List<String>

    // Stergere offline ("tombstone"): dispare din liste, se sterge pe server la sincronizare
    @Query("UPDATE Posts SET isDeleted = 1, isNotSaved = 1, syncStatus = 'PENDING', updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun markDeleted(id: String, updatedAt: String)

    // Serverul a refuzat modificarea: insigna "Sincronizare esuata", cu mesajul serverului
    @Query("UPDATE Posts SET syncStatus = 'FAILED', syncError = :message WHERE id = :id")
    abstract suspend fun markFailed(id: String, message: String)
}