package dev.patrickgold.florisboard.secure.integration

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue

internal data class SecureServerTestEnv(
    val enabled: Boolean,
    val usernamePrefix: String?,
    val password: String?,
) {
    fun assumeConfigured() {
        assumeTrue(
            "Secure server integration tests are disabled. Set SECURE_SERVER_IT=true or -PsecureServerIt=true.",
            enabled,
        )
        assumeTrue(
            "Missing secure server username prefix. Set SECURE_SERVER_IT_USERNAME_PREFIX or secure.server.it.username.prefix in local.properties.",
            !usernamePrefix.isNullOrBlank(),
        )
        assumeTrue(
            "Missing secure server password. Set SECURE_SERVER_IT_PASSWORD or secure.server.it.password in local.properties.",
            !password.isNullOrBlank(),
        )
    }

    companion object {
        private const val ENABLED_ARG = "secureServerIt"
        private const val USERNAME_PREFIX_ARG = "secureServerItUsernamePrefix"
        private const val PASSWORD_ARG = "secureServerItPassword"

        fun load(): SecureServerTestEnv {
            val args = InstrumentationRegistry.getArguments()
            return SecureServerTestEnv(
                enabled = readBoolean(args.getString(ENABLED_ARG)),
                usernamePrefix = args.getString(USERNAME_PREFIX_ARG)?.trim(),
                password = args.getString(PASSWORD_ARG),
            )
        }

        private fun readBoolean(value: String?): Boolean {
            return value?.equals("true", ignoreCase = true) == true ||
                value == "1" ||
                value?.equals("yes", ignoreCase = true) == true
        }
    }
}
