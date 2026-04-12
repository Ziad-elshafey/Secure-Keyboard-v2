package dev.patrickgold.florisboard.secure.auth

import android.net.Uri
import dev.patrickgold.florisboard.BuildConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object GoogleOAuthCoordinator {
    private val callbackUri: Uri by lazy { Uri.parse(BuildConfig.SECURE_OAUTH_REDIRECT_URI) }
    const val requestTimeoutMillis: Long = 5 * 60 * 1000

    private val ipv4HostRegex = Regex(
        """^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$""",
    )

    /**
     * Google returns 400 invalid_request for redirect_uri whose host is a raw IPv4 literal
     * (error details may show `flowName=GeneralOAuthFlow` as a separate field, not part of the URI).
     */
    fun redirectHostBlocksGoogleOAuth(host: String?): Boolean {
        val h = host ?: return false
        if (h.equals("localhost", ignoreCase = true)) return false
        if (h == "127.0.0.1" || h == "::1") return false
        return ipv4HostRegex.matches(h)
    }

    fun googleOAuthRedirectIpv4BlockedMessage(): String =
        "Google sign-in does not allow a bare IP address in the OAuth redirect URL. " +
            "Use a DNS hostname for the redirect (HTTPS), register that exact URL under " +
            "Google Cloud Console → APIs & Services → Credentials → your OAuth client → Authorized redirect URIs, " +
            "then set environment variable SECURE_OAUTH_REDIRECT_URI or Gradle property secureOauthRedirectUri " +
            "to that URL when building the app (API base URL can still be an IP for HTTP cleartext if needed)."

    internal fun normalizeOAuthPath(path: String?): String {
        val raw = path ?: return ""
        return raw.trimEnd('/').ifEmpty { "/" }
    }

    private fun defaultPortForScheme(scheme: String?): Int = when (scheme?.lowercase()) {
        "https" -> 443
        "http" -> 80
        else -> -1
    }

    private fun effectivePort(uri: Uri): Int {
        val explicit = uri.port
        if (explicit != -1) return explicit
        return defaultPortForScheme(uri.scheme)
    }

    data class PendingRequest(
        val state: String,
        val codeVerifier: String,
        val codeChallenge: String,
        val redirectUri: String,
        val startedAtMillis: Long,
    )

    data class CallbackPayload(
        val code: String?,
        val state: String?,
        val error: String?,
    )

    fun createPendingRequest(nowMillis: Long = System.currentTimeMillis()): PendingRequest {
        val codeVerifier = generateUrlSafeRandom(64)
        return PendingRequest(
            state = generateUrlSafeRandom(32),
            codeVerifier = codeVerifier,
            codeChallenge = createCodeChallenge(codeVerifier),
            redirectUri = buildRedirectUri(),
            startedAtMillis = nowMillis,
        )
    }

    fun buildRedirectUri(): String = callbackUri.toString()

    fun isOAuthCallback(uri: Uri?): Boolean {
        if (uri == null) return false
        return uri.scheme.equals(callbackUri.scheme, ignoreCase = true) &&
            uri.host.equals(callbackUri.host, ignoreCase = true) &&
            normalizeOAuthPath(uri.path) == normalizeOAuthPath(callbackUri.path) &&
            effectivePort(uri) == effectivePort(callbackUri)
    }

    fun parseCallback(uri: Uri): CallbackPayload {
        require(isOAuthCallback(uri)) { "Unexpected OAuth callback URI: $uri" }
        return CallbackPayload(
            code = uri.getQueryParameter("code"),
            state = uri.getQueryParameter("state"),
            error = uri.getQueryParameter("error"),
        )
    }

    fun createCodeChallenge(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return base64UrlEncode(digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII)))
    }

    private fun generateUrlSafeRandom(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        return base64UrlEncode(bytes)
    }

    private fun base64UrlEncode(input: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input)
    }
}
