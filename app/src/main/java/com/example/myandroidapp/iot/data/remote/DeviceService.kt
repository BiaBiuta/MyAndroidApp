package com.example.myandroidapp.iot.data.remote

import com.example.myandroidapp.iot.update.UpdateManifest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Trackerul, asa cum il stie serverul (cheia lui publica ramane pe server). */
data class ServerDevice(
    val id: String = "",
    val name: String = "",
    val model: String = "",
    val firmware: String = "",
    val status: String = "",
    val created_at: String = "",
    val revoked_at: String? = null,
    val last_seen_at: String? = null,
    val visit_count: Int = 0
)

/** Un loc trimis de tracker (semnat cu cheia lui). */
data class DeviceVisit(
    val id: String = "",
    val device_id: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val place: String = "",
    val at: String = ""
)

data class RegisterDeviceRequest(
    val id: String,
    val name: String,
    val model: String,
    val firmware: String,
    // cheia publica de identitate a trackerului (SPKI Base64), primita autentificat la asociere
    val publicKey: String
)

/** Endpoint-urile pentru trackere; token-ul JWT e adaugat automat (TokenInterceptor). */
interface DeviceService {
    @POST("/devices")
    suspend fun register(@Body request: RegisterDeviceRequest): ServerDevice

    @GET("/devices")
    suspend fun devices(): List<ServerDevice>

    @POST("/devices/{id}/revoke")
    suspend fun revoke(@Path("id") id: String): ServerDevice

    @POST("/devices/{id}/firmware")
    suspend fun setFirmware(@Path("id") id: String, @Body body: Map<String, String>): ServerDevice

    @GET("/devices/{id}/visits")
    suspend fun visits(@Path("id") id: String): List<DeviceVisit>

    @GET("/device-updates")
    suspend fun updates(): List<UpdateManifest>
}
