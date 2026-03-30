package dev.patrickgold.florisboard.secure.core

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import dev.patrickgold.florisboard.secure.services.DecryptAccessibilityService
import java.lang.ref.WeakReference

/**
 * Shared singleton for coordinating decrypt-on-tap between
 * KeyboardManager and DecryptAccessibilityService.
 * Both run in the same app process so a simple object works.
 */
object DecryptCaptureState {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateLock = Any()
    private var serviceReference: WeakReference<DecryptAccessibilityService>? = null

    @Volatile
    var isCapturing: Boolean = false
        private set

    @Volatile
    var recipientName: String? = null
        private set

    @Volatile
    var onTextCaptured: ((String) -> Unit)? = null
        private set

    /** Reference set by the AccessibilityService in onServiceConnected */
    var serviceInstance: DecryptAccessibilityService?
        get() = serviceReference?.get()
        set(value) {
            serviceReference = value?.let(::WeakReference)
        }

    fun startCapture(recipientName: String, callback: (String) -> Unit) {
        val service = synchronized(stateLock) {
            this.recipientName = recipientName
            this.onTextCaptured = callback
            this.isCapturing = true
            serviceInstance
        }
        service?.let {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                it.showTapCaptureOverlay()
            } else {
                mainHandler.post { it.showTapCaptureOverlay() }
            }
        }
    }

    fun stopCapture() {
        val service = synchronized(stateLock) {
            isCapturing = false
            onTextCaptured = null
            recipientName = null
            serviceInstance
        }
        service?.let {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                it.removeAllOverlays()
            } else {
                mainHandler.post { it.removeAllOverlays() }
            }
        }
    }

    fun deliverText(text: String) {
        val callback: ((String) -> Unit)?
        val service: DecryptAccessibilityService?
        synchronized(stateLock) {
            if (!isCapturing) return
            callback = onTextCaptured
            isCapturing = false
            onTextCaptured = null
            recipientName = null
            service = serviceInstance
        }
        service?.let {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                it.removeAllOverlays()
            } else {
                mainHandler.post { it.removeAllOverlays() }
            }
        }
        callback?.invoke(text)
    }

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, DecryptAccessibilityService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (ComponentName.unflattenFromString(componentName) == expected) {
                return true
            }
        }
        return false
    }
}
