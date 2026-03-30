package dev.patrickgold.florisboard.secure.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

internal object EncryptedPrefsFactory {
    fun create(context: Context, prefsFile: String): SharedPreferences {
        val appContext = context.applicationContext
        val firstFailure = runCatching { buildPrefs(appContext, prefsFile) }.getOrElse { throwable ->
            recoverPrefsFile(appContext, prefsFile)
            return runCatching { buildPrefs(appContext, prefsFile) }.getOrElse { retryThrowable ->
                retryThrowable.addSuppressed(throwable)
                throw IllegalStateException(
                    "Unable to initialize encrypted preferences for $prefsFile after recovery",
                    retryThrowable,
                )
            }
        }
        return firstFailure
    }

    private fun buildPrefs(context: Context, prefsFile: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            prefsFile,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun recoverPrefsFile(context: Context, prefsFile: String) {
        runCatching {
            context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE).edit().clear().commit()
        }
        runCatching {
            val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
            File(sharedPrefsDir, "$prefsFile.xml").delete()
        }
    }
}
