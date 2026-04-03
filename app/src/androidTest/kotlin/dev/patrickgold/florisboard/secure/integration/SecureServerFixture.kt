package dev.patrickgold.florisboard.secure.integration

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.patrickgold.florisboard.BuildConfig
import dev.patrickgold.florisboard.secure.SecureMessagingManager
import dev.patrickgold.florisboard.secure.core.ActiveSecureContact
import dev.patrickgold.florisboard.secure.core.SecureSessionSelection
import dev.patrickgold.florisboard.secure.data.local.AuthTokenManager
import dev.patrickgold.florisboard.secure.data.local.SecureContactStore
import dev.patrickgold.florisboard.secure.data.local.SecureKeyStore
import dev.patrickgold.florisboard.secure.data.local.SecureSessionStore
import dev.patrickgold.florisboard.secure.data.remote.AuthInterceptor
import dev.patrickgold.florisboard.secure.data.remote.SecureApiService
import dev.patrickgold.florisboard.secure.data.remote.SessionResponse
import dev.patrickgold.florisboard.secure.data.remote.StegoDecodeApiService
import dev.patrickgold.florisboard.secure.data.remote.StegoEncodeApiService
import dev.patrickgold.florisboard.secure.data.remote.TokenRefreshAuthenticator
import dev.patrickgold.florisboard.secure.data.remote.UserSearchResult
import dev.patrickgold.florisboard.secure.data.repository.SecureMessagingRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal class SecureServerFixture(
    private val env: SecureServerTestEnv,
) {
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    val tokenManager = AuthTokenManager(context)
    val keyStore = SecureKeyStore(context)
    val contactStore = SecureContactStore(context)
    val sessionStore = SecureSessionStore(context)
    val manager: SecureMessagingManager by lazy { SecureMessagingManager(context) }

    private val loggingLevel = if (BuildConfig.DEBUG) {
        HttpLoggingInterceptor.Level.HEADERS
    } else {
        HttpLoggingInterceptor.Level.NONE
    }

    private val authInterceptor by lazy {
        AuthInterceptor(tokenManager) {
            secureRefreshRetrofit.create(SecureApiService::class.java)
        }
    }

    private val secureRefreshRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.SECURE_API_BASE_URL)
            .client(
                OkHttpClient.Builder()
                    .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build(),
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val secureApiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
            .authenticator(
                TokenRefreshAuthenticator(tokenManager) {
                    secureRefreshRetrofit.create(SecureApiService::class.java)
                },
            )
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val stegoClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply { level = loggingLevel })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val secureApiService: SecureApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.SECURE_API_BASE_URL)
            .client(secureApiClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SecureApiService::class.java)
    }

    val stegoEncodeApiService: StegoEncodeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.STEGO_ENCODE_BASE_URL)
            .client(stegoClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StegoEncodeApiService::class.java)
    }

    val stegoDecodeApiService: StegoDecodeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BuildConfig.STEGO_DECODE_BASE_URL)
            .client(stegoClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(StegoDecodeApiService::class.java)
    }

    val repository: SecureMessagingRepository by lazy {
        SecureMessagingRepository(
            api = secureApiService,
            stegoEncodeApi = stegoEncodeApiService,
            stegoDecodeApi = stegoDecodeApiService,
            tokenManager = tokenManager,
            keyStore = keyStore,
            contactStore = contactStore,
        )
    }

    fun testUsers(): Pair<SecureServerUser, SecureServerUser> {
        val prefix = env.usernamePrefix!!.trim().lowercase()
        val password = env.password!!
        return SecureServerUser("${prefix}_a", password) to SecureServerUser("${prefix}_b", password)
    }

    suspend fun ensureUser(user: SecureServerUser): SecureServerUser {
        val loginResult = repository.login(user.username, user.password)
        val userId = loginResult.fold(
            onSuccess = { it },
            onFailure = {
                repository.register(user.username, user.password).getOrThrow()
            },
        )
        user.userId = userId
        return user
    }

    suspend fun loginAs(user: SecureServerUser): String {
        val userId = repository.login(user.username, user.password).fold(
            onSuccess = { it },
            onFailure = {
                repository.register(user.username, user.password).getOrThrow()
            },
        )
        user.userId = userId
        return userId
    }

    fun resetLocalState(users: Iterable<SecureServerUser> = emptyList()) {
        users.mapNotNull { it.userId }.distinct().forEach { userId ->
            contactStore.clearContactsForActiveUser(userId)
            keyStore.setActiveUser(userId)
            keyStore.clearSessionMaterialForActiveUser()
        }
        sessionStore.clearActiveContact()
        keyStore.clearActiveUser()
        tokenManager.clearAll()
    }

    suspend fun cleanupPairSessions(userA: SecureServerUser, userB: SecureServerUser) {
        loginAs(userA)
        repository.listSessions().getOrThrow()
            .filter { session ->
                session.isActive && (
                    session.initiatorUsername.equals(userA.username, ignoreCase = true) &&
                        session.responderUsername.equals(userB.username, ignoreCase = true) ||
                        session.initiatorUsername.equals(userB.username, ignoreCase = true) &&
                        session.responderUsername.equals(userA.username, ignoreCase = true)
                    )
            }
            .forEach { session ->
                repository.deactivateSession(session.sessionId).getOrThrow()
            }
    }

    suspend fun ensureFreshSession(
        initiator: SecureServerUser,
        responder: SecureServerUser,
    ): String {
        loginAs(initiator)
        val selection = repository.ensureSessionForContact(
            ActiveSecureContact(
                userId = responder.requireUserId(),
                username = responder.username,
                displayName = null,
            ),
        ).getOrThrow()
        return selection.sessionId
    }

    suspend fun listActivePairSessions(
        observer: SecureServerUser,
        firstUsername: String,
        secondUsername: String,
    ): List<SessionResponse> {
        loginAs(observer)
        return repository.listSessions().getOrThrow()
            .filter { it.isActive && it.matchesPair(firstUsername, secondUsername) }
            .sortedByDescending { it.createdAt }
    }

    suspend fun listActivePairSessions(
        observer: SecureServerUser,
        firstUser: SecureServerUser,
        secondUser: SecureServerUser,
    ): List<SessionResponse> {
        return listActivePairSessions(observer, firstUser.username, secondUser.username)
    }

    suspend fun findUserByExactUsername(
        observer: SecureServerUser,
        username: String,
    ): UserSearchResult {
        loginAs(observer)
        return repository.searchUsers(username).getOrThrow()
            .firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?: error("User '$username' not found in exact search")
    }

    suspend fun addExactContact(
        observer: SecureServerUser,
        username: String,
    ) = repository.addContactFromSearchResult(findUserByExactUsername(observer, username)).getOrThrow()

    fun inspectLocalState(): SecureLocalState {
        return SecureLocalState(
            isLoggedIn = repository.isLoggedIn(),
            accessToken = tokenManager.getAccessToken(),
            refreshToken = tokenManager.getRefreshToken(),
            userId = tokenManager.getUserId(),
            username = tokenManager.getUsername(),
            hasActiveUser = keyStore.hasActiveUser(),
            activeContact = sessionStore.getActiveContact(),
            activeSession = sessionStore.getActiveSession(),
        )
    }

    private fun SessionResponse.matchesPair(
        firstUsername: String,
        secondUsername: String,
    ): Boolean {
        return initiatorUsername.equals(firstUsername, ignoreCase = true) &&
            responderUsername.equals(secondUsername, ignoreCase = true) ||
            initiatorUsername.equals(secondUsername, ignoreCase = true) &&
            responderUsername.equals(firstUsername, ignoreCase = true)
    }
}

internal data class SecureServerUser(
    val username: String,
    val password: String,
    var userId: String? = null,
) {
    fun requireUserId(): String = requireNotNull(userId) { "User ID missing for $username" }
}

internal data class SecureLocalState(
    val isLoggedIn: Boolean,
    val accessToken: String?,
    val refreshToken: String?,
    val userId: String?,
    val username: String?,
    val hasActiveUser: Boolean,
    val activeContact: ActiveSecureContact?,
    val activeSession: SecureSessionSelection?,
)
