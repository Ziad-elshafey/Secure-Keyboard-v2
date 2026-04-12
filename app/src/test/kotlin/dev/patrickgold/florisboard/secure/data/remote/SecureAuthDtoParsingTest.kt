package dev.patrickgold.florisboard.secure.data.remote

import com.google.gson.Gson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SecureAuthDtoParsingTest : FunSpec({
    val gson = Gson()

    test("token response parses requires username setup") {
        val parsed = gson.fromJson(
            """{"access_token":"a","refresh_token":"b","token_type":"bearer","expires_in":86400,"requires_username_setup":true}""",
            TokenResponse::class.java,
        )

        parsed.requiresUsernameSetup shouldBe true
        parsed.accessToken shouldBe "a"
    }

    test("user profile parses oauth auth mode and username setup state") {
        val parsed = gson.fromJson(
            """{"user_id":"u1","username":"temp-user","email":"u@example.com","display_name":"Temp","created_at":"2026-01-01T00:00:00Z","last_seen_at":null,"is_active":true,"auth_mode":"google","username_setup_required":true}""",
            UserProfileResponse::class.java,
        )

        parsed.authMode shouldBe "google"
        parsed.usernameSetupRequired shouldBe true
        parsed.username shouldBe "temp-user"
    }
})
