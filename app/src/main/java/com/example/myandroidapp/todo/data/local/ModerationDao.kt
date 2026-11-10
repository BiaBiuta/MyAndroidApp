package com.example.myandroidapp.todo.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.myandroidapp.todo.data.Post
import kotlinx.coroutines.flow.Flow

/** Starea unei raportari. */
object ReportStatus {
    // trimisa, asteapta decizia unui admin
    const val PENDING = "PENDING"
    // adminul a dat dreptate raportarii: postarea a fost eliminata
    const val RESOLVED = "RESOLVED"
    // adminul a respins raportarea: postarea ramane
    const val REJECTED = "REJECTED"
}

/**
 * Raportarea unei postari de catre un utilizator (o singura raportare per utilizator si postare),
 * impreuna cu decizia si raspunsul adminului.
 */
@Entity(tableName = "reports", primaryKeys = ["postId", "reporterId"])
data class ReportEntity(
    val postId: String,
    val reporterId: String,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis(),
    // textul postarii in momentul raportarii (postarea poate fi stearsa ulterior)
    val postSnippet: String = "",
    val status: String = ReportStatus.PENDING,
    // mesajul adminului catre utilizatorul care a raportat
    val adminReply: String = "",
    val resolvedBy: String = "",
    val resolvedAt: Long = 0
)

/**
 * Postare stearsa de autor sau de admin. O tinem minte ca sa nu reapara in lista
 * la urmatorul refresh de pe server (daca serverul nu a sters-o si el).
 */
@Entity(tableName = "hidden_posts")
data class HiddenPostEntity(
    @PrimaryKey val postId: String,
    val hiddenBy: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Mesaj pentru autorul unei postari sterse de un administrator (ex. dupa raportari).
 * Autorul il vede la urmatoarea intrare in aplicatie, pana apasa "Am înțeles".
 */
@Entity(tableName = "author_notices")
data class AuthorNoticeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // ownerKey-ul autorului (user_id de pe postare)
    val recipientKey: String,
    val postId: String,
    // textul postarii sterse (postarea nu mai exista dupa stergere)
    val postSnippet: String,
    // motivele raportarilor (gol daca adminul a sters-o direct, fara raportari)
    val reasons: String,
    // mesajul scris de admin pentru autor
    val message: String,
    val adminId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isRead: Boolean = false
)

/** O postare cu raportari in asteptare, cu numarul lor si motivele (pentru admin). */
data class ReportedPost(
    @Embedded val post: Post,
    val reportCount: Int,
    val reasons: String?
)

@Dao
interface ModerationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: ReportEntity)

    // Admin: doar raportarile la care inca nu s-a raspuns
    @Query(
        "SELECT p.*, COUNT(r.reporterId) AS reportCount, GROUP_CONCAT(r.reason, ' • ') AS reasons " +
                "FROM posts p INNER JOIN reports r ON r.postId = p.id " +
                "WHERE r.status = 'PENDING' AND p.id NOT IN (SELECT postId FROM hidden_posts) " +
                "GROUP BY p.id ORDER BY reportCount DESC"
    )
    fun observeReportedPosts(): Flow<List<ReportedPost>>

    // Ce postari a raportat deja utilizatorul (ca sa afisam "Raportat")
    @Query("SELECT postId FROM reports WHERE reporterId = :reporterId")
    fun observeReportedBy(reporterId: String): Flow<List<String>>

    // "Raportarile mele": toate raportarile utilizatorului, cu statusul si raspunsul adminului
    @Query("SELECT * FROM reports WHERE reporterId = :reporterId ORDER BY createdAt DESC")
    fun observeReportsOf(reporterId: String): Flow<List<ReportEntity>>

    /** Adminul raspunde tuturor raportarilor in asteptare ale unei postari. */
    @Query(
        "UPDATE reports SET status = :status, adminReply = :reply, resolvedBy = :adminId, " +
                "resolvedAt = :resolvedAt WHERE postId = :postId AND status = 'PENDING'"
    )
    suspend fun answerReports(postId: String, status: String, reply: String, adminId: String, resolvedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(hidden: HiddenPostEntity)

    // Admin: fiecare raportare in asteptare (cine a raportat, motivul, cand)
    @Query("SELECT * FROM reports WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun observePendingReports(): Flow<List<ReportEntity>>

    // Motivele raportarilor in asteptare ale unei postari (pentru mesajul catre autor)
    @Query("SELECT reason FROM reports WHERE postId = :postId AND status = 'PENDING'")
    suspend fun pendingReasons(postId: String): List<String>

    // ---------- Mesaje pentru autorii postarilor sterse ----------

    @Insert
    suspend fun insertNotice(notice: AuthorNoticeEntity)

    @Query("SELECT * FROM author_notices WHERE recipientKey = :recipientKey AND isRead = 0 ORDER BY createdAt DESC")
    fun observeUnreadNotices(recipientKey: String): Flow<List<AuthorNoticeEntity>>

    @Query("UPDATE author_notices SET isRead = 1 WHERE recipientKey = :recipientKey")
    suspend fun markNoticesRead(recipientKey: String)
}
