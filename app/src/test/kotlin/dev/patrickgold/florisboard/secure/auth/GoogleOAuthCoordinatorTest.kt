package dev.patrickgold.florisboard.secure.auth

import android.net.Uri
import dev.patrickgold.florisboard.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleOAuthCoordinatorTest {

    @Test
    fun pendingRequestCreatesRedirectUriAndPkceChallenge() {
        val pending = GoogleOAuthCoordinator.createPendingRequest(nowMillis = 1234L)

        assertEquals(BuildConfig.SECURE_OAUTH_REDIRECT_URI, pending.redirectUri)
        assertEquals(GoogleOAuthCoordinator.createCodeChallenge(pending.codeVerifier), pending.codeChallenge)
        assertEquals(1234L, pending.startedAtMillis)
    }

    @Test
    fun parsesCallbackUri() {
        val base = BuildConfig.SECURE_OAUTH_REDIRECT_URI.trimEnd('/')
        val callback = GoogleOAuthCoordinator.parseCallback(
            Uri.parse("$base?code=abc123&state=xyz987"),
        )

        assertEquals("abc123", callback.code)
        assertEquals("xyz987", callback.state)
        assertEquals(null, callback.error)
    }

    @Test
    fun acceptsCallbackUriWithTrailingSlashOnPath() {
        val base = BuildConfig.SECURE_OAUTH_REDIRECT_URI.trimEnd('/')
        val callback = GoogleOAuthCoordinator.parseCallback(
            Uri.parse("$base/?code=abc&state=xyz"),
        )
        assertEquals("abc", callback.code)
        assertEquals("xyz", callback.state)
    }

    @Test
    fun normalizeOAuthPathTrimsTrailingSlashes() {
        assertEquals("/oauth/callback/android", GoogleOAuthCoordinator.normalizeOAuthPath("/oauth/callback/android/"))
        assertEquals("/oauth/callback/android", GoogleOAuthCoordinator.normalizeOAuthPath("/oauth/callback/android"))
    }

    @Test
    fun redirectHostBlocksGoogleOAuthDetectsIpv4() {
        assertTrue(GoogleOAuthCoordinator.redirectHostBlocksGoogleOAuth("18.233.108.148"))
        assertFalse(GoogleOAuthCoordinator.redirectHostBlocksGoogleOAuth("127.0.0.1"))
        assertFalse(GoogleOAuthCoordinator.redirectHostBlocksGoogleOAuth("localhost"))
        assertFalse(GoogleOAuthCoordinator.redirectHostBlocksGoogleOAuth("api.example.com"))
        assertFalse(GoogleOAuthCoordinator.redirectHostBlocksGoogleOAuth(null))
    }

    @Test
    fun rejectsUnexpectedCallbackUri() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            GoogleOAuthCoordinator.parseCallback(Uri.parse("https://example.com/oauth/callback/android"))
        }
        assertEquals("Unexpected OAuth callback URI: https://example.com/oauth/callback/android", ex.message)
    }
}
