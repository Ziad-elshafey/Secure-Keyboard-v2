package dev.patrickgold.florisboard.secure.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.patrickgold.florisboard.secure.core.ActiveSecureContact
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class SecureServerInfrastructureIntegrationTest : BaseSecureServerIntegrationTest() {
    @Test
    fun invalidLoginFailsCleanly() = runBlocking {
        val result = fixture.repository.login(userA.username, "${userA.password}_wrong")
        val state = fixture.inspectLocalState()

        assertTrue("expected invalid login to fail", result.isFailure)
        assertFalse("repository should report logged out", fixture.repository.isLoggedIn())
        assertFalse("local state should report logged out", state.isLoggedIn)
        assertNull("access token should be cleared", state.accessToken)
        assertNull("refresh token should be cleared", state.refreshToken)
        assertNull("user id should be cleared", state.userId)
        assertNull("username should be cleared", state.username)
        assertFalse("active secure user should be cleared", state.hasActiveUser)
    }

    @Test
    fun logoutClearsAuthAndSelectionState() = runBlocking {
        withScenarioRetries {
            fixture.manager.login(userA.username, userA.password).getOrThrow()
            fixture.manager.setActiveContact(
                ActiveSecureContact(
                    userId = userB.requireUserId(),
                    username = userB.username,
                    displayName = null,
                ),
            )
            fixture.manager.setActiveSession("logout-check-session", userB.username)

            assertTrue("expected login state before logout", fixture.manager.isLoggedIn())
            assertNotNull("expected active contact before logout", fixture.manager.getActiveContactSelection())
            assertNotNull("expected active session before logout", fixture.manager.getActiveSessionSelection())

            fixture.manager.logout()

            val state = fixture.inspectLocalState()
            assertFalse("manager should report logged out", fixture.manager.isLoggedIn())
            assertFalse("local state should report logged out", state.isLoggedIn)
            assertNull("access token should be cleared", state.accessToken)
            assertNull("refresh token should be cleared", state.refreshToken)
            assertNull("user id should be cleared", state.userId)
            assertNull("username should be cleared", state.username)
            assertFalse("active secure user should be cleared", state.hasActiveUser)
            assertNull("active contact should be cleared", state.activeContact)
            assertNull("active session should be cleared", state.activeSession)
            assertTrue("protected calls should fail after logout", fixture.repository.listContacts().isFailure)
        }
    }

    @Test
    fun initiatorSecretLossRecreatesUsableSession() = runBlocking {
        withScenarioRetries {
            val oldSessionId = fixture.ensureFreshSession(userA, userB)

            fixture.loginAs(userA)
            val warmUp = fixture.repository.sendMessage(oldSessionId, userB.username, "ping1").getOrThrow()

            fixture.loginAs(userB)
            val warmUpPlaintext = fixture.repository.decryptMessage(warmUp.obfuscatedText, userA.username).getOrThrow().plaintext
            assertEquals("ping1", warmUpPlaintext)

            fixture.loginAs(userA)
            fixture.keyStore.removeSharedSecret(oldSessionId)
            val secondSend = fixture.repository.sendMessage(oldSessionId, userB.username, "ping2").getOrThrow()
            val activePairSessions = fixture.listActivePairSessions(userA, userA, userB)

            assertTrue("expected at least one active session after rekey", activePairSessions.isNotEmpty())
            assertFalse(
                "old session should not stay active after initiator-side secret loss",
                activePairSessions.any { it.sessionId == oldSessionId },
            )

            fixture.loginAs(userB)
            val secondPlaintext = fixture.repository.decryptMessage(secondSend.obfuscatedText, userA.username).getOrThrow().plaintext

            assertEquals("ping2", secondPlaintext)
            assertNotEquals(
                "expected a recreated session after initiator-side secret loss",
                oldSessionId,
                activePairSessions.first().sessionId,
            )
        }
    }

    @Test
    fun contactStateIsIsolatedPerUser() = runBlocking {
        withScenarioRetries {
            fixture.addExactContact(userA, userB.username)

            fixture.loginAs(userA)
            val contactsForA = fixture.repository.listContacts().getOrThrow()
            assertTrue(
                "user A should see user B after adding the contact",
                contactsForA.any { it.username.equals(userB.username, ignoreCase = true) },
            )

            fixture.loginAs(userB)
            val contactsForB = fixture.repository.listContacts().getOrThrow()
            assertFalse(
                "user B should not inherit user A's contacts",
                contactsForB.any { it.username.equals(userA.username, ignoreCase = true) },
            )

            fixture.loginAs(userA)
            fixture.repository.removeContact(userB.username).getOrThrow()
            val contactsForAAfterRemoval = fixture.repository.listContacts().getOrThrow()
            assertFalse(
                "user B should be removed only from user A's local contact store",
                contactsForAAfterRemoval.any { it.username.equals(userB.username, ignoreCase = true) },
            )

            fixture.loginAs(userB)
            val contactsForBAfterRemoval = fixture.repository.listContacts().getOrThrow()
            assertFalse(
                "user B's contact store should stay unchanged",
                contactsForBAfterRemoval.any { it.username.equals(userA.username, ignoreCase = true) },
            )
        }
    }

    @Test
    fun automaticTokenRefreshWorks() = runBlocking {
        withScenarioRetries {
            fixture.loginAs(userA)
            val refreshToken = requireNotNull(fixture.tokenManager.getRefreshToken()) {
                "refresh token missing after login"
            }

            val expiredToken = buildExpiredJwtLikeToken(userA.requireUserId())
            fixture.tokenManager.saveTokens(expiredToken, refreshToken)
            val result = fixture.repository.listSessions()
            val refreshedAccessToken = fixture.tokenManager.getAccessToken()
            val refreshedRefreshToken = fixture.tokenManager.getRefreshToken()

            assertTrue("protected request should succeed after automatic refresh", result.isSuccess)
            assertTrue("access token should be present after refresh", !refreshedAccessToken.isNullOrBlank())
            assertNotEquals(expiredToken, refreshedAccessToken)
            assertTrue("refresh token should remain present", !refreshedRefreshToken.isNullOrBlank())
            assertTrue("repository should stay logged in", fixture.repository.isLoggedIn())
            assertEquals(userA.requireUserId(), fixture.tokenManager.getUserId())
            assertEquals(userA.username, fixture.tokenManager.getUsername())
        }
    }

    @Test
    fun existingSessionIsReusedAcrossRelogin() = runBlocking {
        withScenarioRetries {
            val originalSessionId = fixture.ensureFreshSession(userA, userB)

            fixture.manager.logout()
            assertFalse("logout should clear auth state", fixture.manager.isLoggedIn())

            fixture.manager.login(userA.username, userA.password).getOrThrow()
            val selection = fixture.repository.ensureSessionForContact(
                ActiveSecureContact(
                    userId = userB.requireUserId(),
                    username = userB.username,
                    displayName = null,
                ),
            ).getOrThrow()

            val sendResult = fixture.repository.sendMessage(selection.sessionId, userB.username, "relogin").getOrThrow()

            fixture.loginAs(userB)
            val decrypted = fixture.repository.decryptMessage(sendResult.obfuscatedText, userA.username).getOrThrow().plaintext

            assertEquals("expected the existing session to be reused after relogin", originalSessionId, selection.sessionId)
            assertEquals("relogin", decrypted)
        }
    }

    private fun buildExpiredJwtLikeToken(userId: String): String {
        val header = base64Url("""{"alg":"HS256","typ":"JWT"}""")
        val payload = base64Url("""{"sub":"$userId","exp":1,"iat":1}""")
        return "$header.$payload.invalid-signature"
    }

    private fun base64Url(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
    }
}
