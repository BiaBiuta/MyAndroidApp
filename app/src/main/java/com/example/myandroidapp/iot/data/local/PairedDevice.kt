package com.example.myandroidapp.iot.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

object DeviceStatus {
    const val ACTIVE = "ACTIVE"
    const val REVOKED = "REVOKED"
}

/**
 * Un tracker asociat cu acest telefon, pentru contul ownerKey.
 * pairingKey = cheia de asociere K, CRIPTATA cu o cheie din Android Keystore (SecureStore);
 * dupa revocare e stearsa ("").
 */
@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val id: String,
    val ownerKey: String,
    val name: String,
    val model: String,
    val firmware: String,
    // adresa trackerului in reteaua locala, ex. "10.0.2.2:4000"
    val host: String,
    val identityPublicKey: String,
    val pairingKey: String,
    val status: String,
    val pairedAt: Long
)

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices WHERE ownerKey = :ownerKey ORDER BY pairedAt DESC")
    fun observeForOwner(ownerKey: String): Flow<List<PairedDeviceEntity>>

    @Query("SELECT * FROM paired_devices WHERE id = :id AND ownerKey = :ownerKey")
    suspend fun find(id: String, ownerKey: String): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedDeviceEntity)

    @Query("UPDATE paired_devices SET status = :status, pairingKey = CASE WHEN :status = 'REVOKED' THEN '' ELSE pairingKey END WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Query("UPDATE paired_devices SET firmware = :firmware WHERE id = :id")
    suspend fun setFirmware(id: String, firmware: String)

    @Query("DELETE FROM paired_devices WHERE id = :id AND ownerKey = :ownerKey")
    suspend fun delete(id: String, ownerKey: String)
}
