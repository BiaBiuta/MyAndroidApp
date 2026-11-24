package com.example.myandroidapp.community.data

import android.util.Log
import com.example.myandroidapp.core.data.remote.OfflineException
import com.example.myandroidapp.core.data.remote.ServerException
import com.example.myandroidapp.core.data.remote.apiCall
import com.example.myandroidapp.core.isoNow
import com.example.myapp.todo.data.local.ItemDao
import com.example.myandroidapp.todo.data.remote.Comment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Partea "comunitate" a aplicatiei:
 *  - calatoriile proprii (offline-first, ca postarile) si ale altora (de pe server);
 *  - profilurile publice si urmarirea altor calatori (lista "Urmariti" pastrata si local);
 *  - postarile salvate (doar pe telefon, merg si offline);
 *  - editarea si aprecierea comentariilor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommunityRepository(
    private val service: CommunityService,
    private val tripDao: TripDao,
    private val followDao: FollowDao,
    private val savedDao: SavedPostDao,
    private val itemDao: ItemDao,
    private val ownerKeys: Flow<String>,
    private val currentOwnerKey: suspend () -> String,
    private val scheduleSync: () -> Unit = {}
) {
    private val syncMutex = Mutex()

    private fun <T> perOwner(block: (String) -> Flow<T>, empty: T): Flow<T> =
        ownerKeys.distinctUntilChanged().flatMapLatest { owner -> if (owner.isBlank()) flowOf(empty) else block(owner) }

    // ---------------------------------------------------------------------------
    // Calatorii
    // ---------------------------------------------------------------------------

    val myTrips: Flow<List<TripEntity>> = perOwner({ tripDao.observe(it) }, emptyList())

    suspend fun trip(id: String): TripEntity? = tripDao.find(id)

    /** Calatorie noua sau editata: intai pe telefon, apoi pe server (acum sau la sincronizare). */
    suspend fun saveTrip(id: String?, title: String, description: String, startDate: Long, endDate: Long): TripEntity {
        val owner = currentOwnerKey()
        val trip = TripEntity(
            id = id ?: java.util.UUID.randomUUID().toString(),
            ownerKey = owner,
            title = title.trim(),
            description = description.trim(),
            startDate = startDate,
            endDate = endDate,
            updatedAt = isoNow(),
            syncStatus = "PENDING"
        )
        tripDao.upsert(trip)
        if (!syncTrips()) scheduleSync()
        return trip
    }

    /** Stergere (si offline): postarile raman, doar ies din calatorie. */
    suspend fun deleteTrip(id: String) {
        val trip = tripDao.find(id) ?: return
        itemDao.clearTrip(id)
        tripDao.upsert(trip.copy(isDeleted = true, syncStatus = "PENDING", updatedAt = isoNow()))
        if (!syncTrips()) scheduleSync()
    }

    /**
     * Trimite calatoriile din coada. Apelata si de sincronizarea postarilor, INAINTEA lor (o postare
     * poate referi o calatorie creata offline). false = serverul nu e disponibil acum.
     */
    suspend fun syncTrips(): Boolean = syncMutex.withLock {
        val owner = currentOwnerKey()
        if (owner.isBlank()) return@withLock true
        for (trip in tripDao.pending(owner)) {
            try {
                if (trip.isDeleted) {
                    val response = apiCall { service.deleteTrip(trip.id) }
                    if (response.isSuccessful || response.code() == 404 || response.code() == 410) tripDao.delete(trip.id)
                } else {
                    apiCall { service.saveTrip(trip.id, TripBody(trip.title, trip.description, trip.startDate, trip.endDate)) }
                    tripDao.upsert(trip.copy(syncStatus = "SYNCED", syncError = ""))
                }
            } catch (e: OfflineException) {
                return@withLock false
            } catch (e: ServerException) {
                when (e.status) {
                    401 -> return@withLock false
                    410 -> tripDao.delete(trip.id)
                    else -> tripDao.markFailed(trip.id, e.message)
                }
            }
        }
        true
    }

    /** Calatoriile proprii de pe server (ex. create pe alt telefon); cele modificate local raman. */
    suspend fun refreshTrips() {
        val owner = currentOwnerKey()
        if (owner.isBlank()) return
        try {
            val remote = apiCall { service.trips(owner) }
            for (trip in remote) {
                val local = tripDao.find(trip.id)
                if (local != null && local.syncStatus != "SYNCED") continue
                tripDao.upsert(
                    TripEntity(trip.id, owner, trip.title, trip.description, trip.start_date, trip.end_date, trip.updated_at, "SYNCED")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshTrips", e)
        }
    }

    /** Calatoriile altui calator (doar online). */
    suspend fun tripsOf(userId: String): List<RemoteTrip> = apiCall { service.trips(userId) }

    // ---------------------------------------------------------------------------
    // Profiluri si urmarire
    // ---------------------------------------------------------------------------

    val following: Flow<List<String>> = perOwner({ followDao.observe(it) }, emptyList())

    suspend fun profile(userId: String): PublicProfile = apiCall { service.profile(userId) }

    /** Urmareste / nu mai urmari (online: schimba ce vede celalalt la "urmaritori"). */
    suspend fun setFollowing(userId: String, follow: Boolean): PublicProfile {
        val profile = apiCall { if (follow) service.follow(userId) else service.unfollow(userId) }
        val owner = currentOwnerKey()
        if (follow) followDao.add(FollowEntity(owner, userId)) else followDao.remove(owner, userId)
        return profile
    }

    suspend fun refreshFollowing() {
        val owner = currentOwnerKey()
        if (owner.isBlank()) return
        try {
            val ids = apiCall { service.following() }
            followDao.clear(owner)
            ids.forEach { followDao.add(FollowEntity(owner, it)) }
        } catch (e: Exception) {
            Log.w(TAG, "refreshFollowing", e)
        }
    }

    // ---------------------------------------------------------------------------
    // Salvate (pe telefon)
    // ---------------------------------------------------------------------------

    val saved: Flow<List<String>> = perOwner({ savedDao.observe(it) }, emptyList())

    suspend fun setSaved(postId: String, saved: Boolean) {
        val owner = currentOwnerKey()
        if (owner.isBlank()) return
        if (saved) savedDao.save(SavedPostEntity(owner, postId, System.currentTimeMillis()))
        else savedDao.remove(owner, postId)
    }

    // ---------------------------------------------------------------------------
    // Comentarii: editare si aprecieri
    // ---------------------------------------------------------------------------

    suspend fun editComment(id: String, text: String): Comment = apiCall { service.editComment(id, mapOf("text" to text)) }

    suspend fun setCommentLiked(id: String, liked: Boolean): Comment =
        apiCall { if (liked) service.likeComment(id) else service.unlikeComment(id) }

    /** Dupa login / la reimprospatare: calatoriile si lista "Urmariti" de pe server. */
    suspend fun refreshAll() {
        syncTrips()
        refreshTrips()
        refreshFollowing()
    }

    companion object {
        private const val TAG = "CommunityRepository"
    }
}
