package dev.patrickgold.florisboard.secure.integration

import java.io.IOException
import java.net.ProtocolException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import retrofit2.HttpException

internal abstract class BaseSecureServerIntegrationTest {
    protected lateinit var env: SecureServerTestEnv
    protected lateinit var fixture: SecureServerFixture
    protected lateinit var userA: SecureServerUser
    protected lateinit var userB: SecureServerUser

    @Before
    fun baseSetUp() = runBlocking {
        env = SecureServerTestEnv.load()
        env.assumeConfigured()

        fixture = SecureServerFixture(env)
        val users = fixture.testUsers()
        userA = fixture.ensureUser(users.first)
        userB = fixture.ensureUser(users.second)

        resetScenarioState()
    }

    @After
    fun baseTearDown() = runBlocking {
        if (!::fixture.isInitialized) return@runBlocking
        runCatching { fixture.cleanupPairSessions(userA, userB) }
        fixture.resetLocalState(listOf(userA, userB))
    }

    protected suspend fun withScenarioRetries(
        maxAttempts: Int = 3,
        block: suspend () -> Unit,
    ) {
        var lastError: Throwable? = null
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) {
                resetScenarioState()
                delay(attempt * 1_000L)
            }

            try {
                block()
                return
            } catch (t: Throwable) {
                lastError = t
                if (!t.isRetryableScenarioFailure() || attempt == maxAttempts - 1) {
                    throw t
                }
            }
        }

        throw lastError ?: IllegalStateException("Scenario failed without an exception")
    }

    protected suspend fun resetScenarioState() {
        fixture.resetLocalState(listOf(userA, userB))
        fixture.cleanupPairSessions(userA, userB)
        fixture.resetLocalState(listOf(userA, userB))
    }

    private fun Throwable.isRetryableScenarioFailure(): Boolean {
        return when (this) {
            is AssertionError -> true
            is HttpException -> code() == 401 || code() >= 500
            is IOException -> true
            is ProtocolException -> true
            else -> cause?.isRetryableScenarioFailure() == true
        }
    }
}
