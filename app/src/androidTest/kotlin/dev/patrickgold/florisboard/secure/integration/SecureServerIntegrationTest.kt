package dev.patrickgold.florisboard.secure.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class SecureServerIntegrationTest : BaseSecureServerIntegrationTest() {
    @Test
    fun sessionBootstrapCreatesUsableSession() = runBlocking {
        withScenarioRetries {
            val sessionId = fixture.ensureFreshSession(userA, userB)

            assertTrue("session id should not be blank", sessionId.isNotBlank())

            val activeSessions = fixture.listActivePairSessions(userA, userA, userB)
            assertTrue(
                "expected an active session between test users",
                activeSessions.any { it.sessionId == sessionId },
            )
        }
    }

    @Test
    fun endToEndSendAndDecryptRoundTripsPlaintext() = runBlocking {
        withScenarioRetries {
            val sessionId = fixture.ensureFreshSession(userA, userB)
            val plaintext = "round trip secure message\nwith punctuation !? and emoji \uD83D\uDE80"

            fixture.loginAs(userA)
            val sendResult = fixture.repository.sendMessage(sessionId, userB.username, plaintext).getOrThrow()

            fixture.loginAs(userB)
            val decryptResult = fixture.repository.decryptMessage(sendResult.obfuscatedText, userA.username).getOrThrow()

            assertEquals(plaintext, decryptResult.plaintext)
        }
    }

    @Test
    fun repeatedSendsReuseExistingSession() = runBlocking {
        withScenarioRetries {
            val sessionId = fixture.ensureFreshSession(userA, userB)

            fixture.loginAs(userA)
            val first = fixture.repository.sendMessage(sessionId, userB.username, "first message").getOrThrow()
            val second = fixture.repository.sendMessage(sessionId, userB.username, "second message").getOrThrow()

            val pairSessions = fixture.listActivePairSessions(userA, userA, userB)

            fixture.loginAs(userB)
            val firstPlaintext = fixture.repository.decryptMessage(first.obfuscatedText, userA.username).getOrThrow().plaintext
            val secondPlaintext = fixture.repository.decryptMessage(second.obfuscatedText, userA.username).getOrThrow().plaintext

            assertEquals("first message", firstPlaintext)
            assertEquals("second message", secondPlaintext)
            assertTrue("expected at least one active session for the pair", pairSessions.isNotEmpty())
            assertTrue(
                "send reuse should keep the original session active",
                pairSessions.any { it.sessionId == sessionId },
            )
        }
    }

    @Test
    @Ignore("Disabled for low-cost live-server runs.")
    fun extremePlaintextRoundTripsThroughSecureServer() = runBlocking {
        val sessionId = fixture.ensureFreshSession(userA, userB)
        val plaintext = buildExtremePlaintext()

        fixture.loginAs(userA)
        val sendResult = fixture.repository.sendMessage(sessionId, userB.username, plaintext).getOrThrow()

        fixture.loginAs(userB)
        val decryptResult = fixture.repository.decryptMessage(sendResult.obfuscatedText, userA.username).getOrThrow()

        assertEquals(plaintext, decryptResult.plaintext)
        assertTrue(
            "expected long plaintext payload",
            plaintext.toByteArray(Charsets.UTF_8).size >= 2000,
        )
    }

    @Test
    fun invalidCiphertextFailsDeterministically() = runBlocking {
        fixture.ensureFreshSession(userA, userB)

        fixture.loginAs(userB)
        val firstError = fixture.repository.decryptMessage("not valid secure text", userA.username)
            .exceptionOrNull()?.message.orEmpty()
        val secondError = fixture.repository.decryptMessage("not valid secure text", userA.username)
            .exceptionOrNull()?.message.orEmpty()

        assertFalse("invalid ciphertext should fail", firstError.isBlank())
        assertEquals(firstError, secondError)
    }

    @Test
    fun decryptFailsForWrongSender() = runBlocking {
        val sessionId = fixture.ensureFreshSession(userA, userB)

        fixture.loginAs(userA)
        val sendResult = fixture.repository.sendMessage(sessionId, userB.username, "sender mismatch case").getOrThrow()

        fixture.loginAs(userB)
        val result = fixture.repository.decryptMessage(sendResult.obfuscatedText, "${userA.username}_wrong")

        assertTrue("expected wrong-sender decrypt to fail", result.isFailure)
        assertTrue(
            "expected a session lookup failure message",
            result.exceptionOrNull()?.message.orEmpty().contains("No active session found", ignoreCase = true),
        )
    }

    @Test
    fun decryptReestablishesSharedSecretAfterLocalSecretLoss() = runBlocking {
        withScenarioRetries {
            val sessionId = fixture.ensureFreshSession(userA, userB)

            fixture.loginAs(userA)
            val firstMessage = fixture.repository.sendMessage(sessionId, userB.username, "warm up").getOrThrow()

            fixture.loginAs(userB)
            val firstPlaintext = fixture.repository.decryptMessage(firstMessage.obfuscatedText, userA.username).getOrThrow().plaintext
            fixture.keyStore.removeSharedSecret(sessionId)

            fixture.loginAs(userA)
            val secondMessage = fixture.repository.sendMessage(sessionId, userB.username, "after local secret loss").getOrThrow()

            fixture.loginAs(userB)
            val secondPlaintext = fixture.repository.decryptMessage(secondMessage.obfuscatedText, userA.username).getOrThrow().plaintext

            assertEquals("warm up", firstPlaintext)
            assertEquals("after local secret loss", secondPlaintext)
        }
    }

    @Test
    fun sendAfterSessionDeactivationCreatesFreshUsableSession() = runBlocking {
        withScenarioRetries {
            val oldSessionId = fixture.ensureFreshSession(userA, userB)

            fixture.loginAs(userA)
            fixture.repository.deactivateSession(oldSessionId).getOrThrow()
            val sendResult = fixture.repository.sendMessageToUser(userB.username, "after deactivation").getOrThrow()

            val newSessionId = fixture.ensureFreshSession(userA, userB)
            fixture.loginAs(userB)
            val decrypted = fixture.repository.decryptMessage(sendResult.obfuscatedText, userA.username).getOrThrow().plaintext

            assertEquals("after deactivation", decrypted)
            assertNotEquals("expected a fresh session after deactivation", oldSessionId, newSessionId)
        }
    }

    private fun buildExtremePlaintext(): String {
        val block = buildString {
            append("Secure integration payload line 1\n")
            append("Emoji: \uD83D\uDE80 \uD83D\uDD10 \uD83E\uDDEA\n")
            append(
                "Scripts: English " +
                    "\u0627\u0644\u0639\u0631\u0628\u064a\u0629 " +
                    "\u0939\u093f\u0928\u094d\u0926\u0940 " +
                    "\u65e5\u672c\u8a9e " +
                    "\u0440\u0443\u0441\u0441\u043a\u0438\u0439\n",
            )
            append("Punctuation: !@#$%^&*()_+-=[]{};':\",.<>/?\\|\n")
            append("Whitespace:\tindent\tand trailing spaces   \n")
        }
        val repeated = buildString {
            repeat(9) { index ->
                append("[$index] ")
                append(block)
            }
        }
        return repeated
    }
}
