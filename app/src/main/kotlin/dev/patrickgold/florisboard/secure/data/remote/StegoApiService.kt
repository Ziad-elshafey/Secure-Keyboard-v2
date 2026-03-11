package dev.patrickgold.florisboard.secure.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

interface StegoEncodeApiService {
    @POST("/")
    suspend fun encode(@Body request: StegoEncodeRequest): StegoEncodeResponse
}

interface StegoDecodeApiService {
    @POST("/")
    suspend fun decode(@Body request: StegoDecodeRequest): StegoDecodeResponse
}

data class StegoEncodeRequest(
    @SerializedName("context") val context: String,
    @SerializedName("bits") val bits: String,
)

data class StegoEncodeResponse(
    @SerializedName("text") val text: String,
    @SerializedName("ac_token_count") val acTokenCount: Int? = null,
    @SerializedName("prompt") val prompt: String? = null,
)

data class StegoDecodeRequest(
    @SerializedName("text") val text: String,
)

data class StegoDecodeResponse(
    @SerializedName("bits") val bits: String,
)