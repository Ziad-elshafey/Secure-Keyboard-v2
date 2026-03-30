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
    @SerializedName("temperature") val temperature: Double = 1.2,
    @SerializedName("top_k") val topK: Int = 80,
    @SerializedName("repetition_penalty") val repetitionPenalty: Double = 1.1,
    @SerializedName("prompt_mode") val promptMode: String = "short_starter",
)

data class StegoEncodeResponse(
    @SerializedName("text") val text: String,
    @SerializedName("ac_token_count") val acTokenCount: Int? = null,
    @SerializedName("prompt") val prompt: String? = null,
)

data class StegoDecodeRequest(
    @SerializedName("text") val text: String,
    @SerializedName("temperature") val temperature: Double = 1.2,
    @SerializedName("top_k") val topK: Int = 80,
    @SerializedName("repetition_penalty") val repetitionPenalty: Double = 1.1,
    @SerializedName("prompt_mode") val promptMode: String = "short_starter",
)

data class StegoDecodeResponse(
    @SerializedName("bits") val bits: String,
)
