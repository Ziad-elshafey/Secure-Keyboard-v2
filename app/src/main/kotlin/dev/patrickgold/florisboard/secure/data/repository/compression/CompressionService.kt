package dev.patrickgold.florisboard.secure.data.repository.compression

import android.content.Context
import dev.patrickgold.florisboard.secure.data.repository.compression.custom.TextCompressor

object CompressionService {
    private const val vocabAssetName = "dailydialog_vocab.json"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    private val compressor: TextCompressor by lazy {
        TextCompressor().apply {
            try {
                val context = checkNotNull(appContext) {
                    "CompressionService.initialize(context) must be called before first use"
                }
                val vocabContent = context.assets.open(vocabAssetName).bufferedReader().use { it.readText() }
                loadVocab(vocabContent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun compress(text: String): ByteArray {
        if (text.isEmpty()) return byteArrayOf()

        return try {
            compressor.compress(text)
        } catch (_: Exception) {
            text.toByteArray(Charsets.UTF_8)
        }
    }

    fun decompress(compressedBytes: ByteArray): String {
        if (compressedBytes.isEmpty()) return ""

        return try {
            compressor.decompress(compressedBytes)
        } catch (_: Exception) {
            String(compressedBytes, Charsets.UTF_8)
        }
    }

    fun getCompressionRatio(originalSize: Int, compressedSize: Int): Float {
        return if (compressedSize > 0) {
            originalSize.toFloat() / compressedSize
        } else {
            1.0f
        }
    }

    fun getSavingsPercent(originalSize: Int, compressedSize: Int): Float {
        return if (originalSize > 0) {
            (1 - compressedSize.toFloat() / originalSize) * 100
        } else {
            0f
        }
    }

    fun getBitsPerWord(text: String, compressedSize: Int): Float {
        val wordCount = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        return if (wordCount > 0) {
            (compressedSize * 8).toFloat() / wordCount
        } else {
            0f
        }
    }
}