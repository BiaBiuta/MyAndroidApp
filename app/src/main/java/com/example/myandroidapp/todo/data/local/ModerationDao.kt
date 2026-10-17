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

/** Raportarea unei postari de catre un utilizator (o singura raportare per utilizator). */
@Entity(tableName = "reports", primaryKeys = ["postId", "reporterId"])
data class ReportEntity(
    val postId: String,
    val reporterId: String,
    val reason: String,
    val createdAt: Long = System.currentTimeMillis()
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

/** O postare raportata, cu numarul de raportari si motivele (pentru admin). */
data class ReportedPost(
    @Embedded val post: Post,
    val reportCount: Int,
    val reasons: String?
)

@Dao
interface ModerationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReport(report: ReportEntity)

    @Query(
        "SELECT p.*, COUNT(r.reporterId) AS reportCount, GROUP_CONCAT(r.reason, ' • ') AS reasons " +
            "FROM posts p INNER JOIN reports r ON r.postId = p.id " +
            "WHERE p.id NOT IN (SELECT postId FROM hidden_posts) " +
            "GROUP BY p.id ORDER BY reportCount DESC"
    )
    fun observeReportedPosts(): Flow<List<ReportedPost>>

    // Ce postari a raportat deja utilizatorul (ca sa afisam "Raportat")
    @Query("SELECT postId FROM reports WHERE reporterId = :reporterId")
    fun observeReportedBy(reporterId: String): Flow<List<String>>

    @Query("DELETE FROM reports WHERE postId = :postId")
    suspend fun deleteReportsFor(postId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun hide(hidden: HiddenPostEntity)
}
