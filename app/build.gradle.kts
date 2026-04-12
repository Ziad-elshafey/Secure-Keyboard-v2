/*
 * Copyright (C) 2022-2025 The FlorisBoard Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.Project
import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.agp.application)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.mikepenz.aboutlibraries)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlinx.kover)
}

val projectMinSdk: String by project
val projectTargetSdk: String by project
val projectCompileSdk: String by project
val projectVersionCode: String by project
val projectVersionName: String by project
val projectVersionNameSuffix = projectVersionName.substringAfter("-", "").let { suffix ->
    if (suffix.isNotEmpty()) {
        "-$suffix"
    } else {
        suffix
    }
}
// rootProject.file(".kotlin/sessions").mkdirs()
// projectDir.resolve(".kotlin/sessions").mkdirs()
// File(System.getProperty("user.home"), ".kotlin/sessions").mkdirs()

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.set(listOf(
            "-opt-in=kotlin.contracts.ExperimentalContracts",
            "-jvm-default=enable",
            "-Xwhen-guards",
            "-Xexplicit-backing-fields",
            "-Xcontext-parameters",
            "-XXLanguage:+LocalTypeAliases",
        ))
    }
}

configure<ApplicationExtension> {
    namespace = "dev.patrickgold.florisboard"
    compileSdk = projectCompileSdk.toInt()
    buildToolsVersion = tools.versions.buildTools.get()
    ndkVersion = tools.versions.ndk.get()

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    defaultConfig {
        applicationId = "dev.patrickgold.florisboard"
        minSdk = projectMinSdk.toInt()
        targetSdk = projectTargetSdk.toInt()
        versionCode = projectVersionCode.toInt()
        versionName = projectVersionName.substringBefore("-")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BUILD_COMMIT_HASH", "\"${getGitCommitHash().get()}\"")
        buildConfigField("String", "FLADDONS_API_VERSION", "\"v~draft2\"")
        buildConfigField("String", "FLADDONS_STORE_URL", "\"beta.addons.florisboard.org\"")
        manifestPlaceholders["secureUsesCleartextTraffic"] = "false"
        manifestPlaceholders["secureOAuthCallbackScheme"] = "https"
        manifestPlaceholders["secureOAuthCallbackHost"] = "secure-oauth-host.placeholder"

        sourceSets {
            maybeCreate("main").apply {
                assets.directories += "src/main/assets"
            }
        }
    }

    bundle {
        language {
            // We disable language split because FlorisBoard does not use
            // runtime Google Play Service APIs and thus cannot dynamically
            // request to download the language resources for a specific locale.
            enableSplit = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        named("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug+${getGitCommitHash(short = true).get()}"

            isDebuggable = true
            isJniDebuggable = false
            val secureApiBaseUrl = project.resolveSecureEndpoint("secureApiBaseUrl", "SECURE_API_BASE_URL", fallbackValue = "http://18.233.108.148:8000/")
            val oauthRedirectUri = project.resolveSecureOAuthRedirectUri(secureApiBaseUrl)
            buildConfigField("String", "SECURE_API_BASE_URL", quoted(secureApiBaseUrl))
            buildConfigField("String", "SECURE_OAUTH_REDIRECT_URI", quoted(oauthRedirectUri))
            buildConfigField("String", "STEGO_ENCODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalEncodeBaseUrl", "SECURE_MODAL_ENCODE_BASE_URL", fallbackValue = "https://modalcd--encode-nocontext.modal.run/", requireHttps = true)))
            buildConfigField("String", "STEGO_DECODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalDecodeBaseUrl", "SECURE_MODAL_DECODE_BASE_URL", fallbackValue = "https://modalcd--decode-nocontext.modal.run/", requireHttps = true)))
            manifestPlaceholders["secureUsesCleartextTraffic"] = "true"
            // OAuth redirect must stay https: Google rejects non-loopback http redirect_uri (400 invalid_request).
            manifestPlaceholders["secureOAuthCallbackScheme"] = "https"
            manifestPlaceholders["secureOAuthCallbackHost"] = project.manifestOAuthHostFromRedirect(oauthRedirectUri)
        }

        create("beta") {
            applicationIdSuffix = ".beta"
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
            val secureApiBaseUrl = project.resolveSecureEndpoint("secureApiBaseUrl", "SECURE_API_BASE_URL", fallbackValue = "https://secure-api-placeholder.invalid/", requireHttps = true, disallowPlaceholder = true)
            val oauthRedirectUri = project.resolveSecureOAuthRedirectUri(secureApiBaseUrl)
            buildConfigField("String", "SECURE_API_BASE_URL", quoted(secureApiBaseUrl))
            buildConfigField("String", "SECURE_OAUTH_REDIRECT_URI", quoted(oauthRedirectUri))
            buildConfigField("String", "STEGO_ENCODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalEncodeBaseUrl", "SECURE_MODAL_ENCODE_BASE_URL", fallbackValue = "https://modalcd--encode-nocontext.modal.run/", requireHttps = true)))
            buildConfigField("String", "STEGO_DECODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalDecodeBaseUrl", "SECURE_MODAL_DECODE_BASE_URL", fallbackValue = "https://modalcd--decode-nocontext.modal.run/", requireHttps = true)))
            manifestPlaceholders["secureOAuthCallbackScheme"] = "https"
            manifestPlaceholders["secureOAuthCallbackHost"] = project.manifestOAuthHostFromRedirect(oauthRedirectUri)
        }

        named("release") {
            versionNameSuffix = projectVersionNameSuffix

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = true
            val secureApiBaseUrl = project.resolveSecureEndpoint("secureApiBaseUrl", "SECURE_API_BASE_URL", fallbackValue = "https://secure-api-placeholder.invalid/", requireHttps = true, disallowPlaceholder = true)
            val oauthRedirectUri = project.resolveSecureOAuthRedirectUri(secureApiBaseUrl)
            buildConfigField("String", "SECURE_API_BASE_URL", quoted(secureApiBaseUrl))
            buildConfigField("String", "SECURE_OAUTH_REDIRECT_URI", quoted(oauthRedirectUri))
            buildConfigField("String", "STEGO_ENCODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalEncodeBaseUrl", "SECURE_MODAL_ENCODE_BASE_URL", fallbackValue = "https://modalcd--encode-nocontext.modal.run/", requireHttps = true)))
            buildConfigField("String", "STEGO_DECODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalDecodeBaseUrl", "SECURE_MODAL_DECODE_BASE_URL", fallbackValue = "https://modalcd--decode-nocontext.modal.run/", requireHttps = true)))
            manifestPlaceholders["secureOAuthCallbackScheme"] = "https"
            manifestPlaceholders["secureOAuthCallbackHost"] = project.manifestOAuthHostFromRedirect(oauthRedirectUri)
        }

        create("benchmark") {
            initWith(getByName("release"))

            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench+${getGitCommitHash(short = true).get()}"

            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            val secureApiBaseUrl = project.resolveSecureEndpoint("secureApiBaseUrl", "SECURE_API_BASE_URL", fallbackValue = "https://secure-api-placeholder.invalid/", requireHttps = true, disallowPlaceholder = true)
            val oauthRedirectUri = project.resolveSecureOAuthRedirectUri(secureApiBaseUrl)
            buildConfigField("String", "SECURE_API_BASE_URL", quoted(secureApiBaseUrl))
            buildConfigField("String", "SECURE_OAUTH_REDIRECT_URI", quoted(oauthRedirectUri))
            buildConfigField("String", "STEGO_ENCODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalEncodeBaseUrl", "SECURE_MODAL_ENCODE_BASE_URL", fallbackValue = "https://modalcd--encode-nocontext.modal.run/", requireHttps = true)))
            buildConfigField("String", "STEGO_DECODE_BASE_URL", quoted(project.resolveSecureEndpoint("secureModalDecodeBaseUrl", "SECURE_MODAL_DECODE_BASE_URL", fallbackValue = "https://modalcd--decode-nocontext.modal.run/", requireHttps = true)))
            manifestPlaceholders["secureOAuthCallbackScheme"] = "https"
            manifestPlaceholders["secureOAuthCallbackHost"] = project.manifestOAuthHostFromRedirect(oauthRedirectUri)
        }
    }

    aboutLibraries {
        collect {
            configPath = file("src/main/config")
        }
    }

    lint {
        baseline = file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

val secureServerItFromLocalProperties: Map<String, String> = run {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run emptyMap()
    val p = Properties()
    f.inputStream().use { p.load(it) }
    fun get(key: String) = p.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
    mapOf(
        "secureServerIt" to get("secure.server.it"),
        "secureServerItUsernamePrefix" to get("secure.server.it.username.prefix"),
        "secureServerItPassword" to get("secure.server.it.password"),
    ).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}

tasks.withType<Test> {
    testLogging {
        events = setOf(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
    useJUnitPlatform()

    val secureServerTestProps = mapOf(
        "secureServerIt" to "SECURE_SERVER_IT",
        "secureServerItUsernamePrefix" to "SECURE_SERVER_IT_USERNAME_PREFIX",
        "secureServerItPassword" to "SECURE_SERVER_IT_PASSWORD",
    )
    secureServerTestProps.forEach { (propertyName, envName) ->
        val configuredValue = providers.gradleProperty(propertyName)
            .orElse(providers.environmentVariable(envName))
            .orNull
            ?: secureServerItFromLocalProperties[propertyName]
        if (configuredValue != null) {
            systemProperty(propertyName, configuredValue)
        }
    }
}

kover {
    useJacoco()
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    // testImplementation(composeBom)
    // androidTestImplementation(composeBom)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.autofill)
    implementation(libs.androidx.collection.ktx)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.emoji2)
    implementation(libs.androidx.emoji2.views)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.window.core)
    implementation(libs.bouncycastle)
    implementation(libs.cache4k)
    implementation(libs.gson)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mikepenz.aboutlibraries.core)
    implementation(libs.mikepenz.aboutlibraries.compose)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.patrickgold.compose.tooltip)
    implementation(libs.patrickgold.jetpref.datastore.model)
    ksp(libs.patrickgold.jetpref.datastore.model.processor)
    implementation(libs.patrickgold.jetpref.datastore.ui)
    implementation(libs.patrickgold.jetpref.material.ui)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.tink)

    implementation(projects.lib.android)
    implementation(projects.lib.color)
    implementation(projects.lib.compose)
    implementation(projects.lib.kotlin)
    implementation(projects.lib.native)
    implementation(projects.lib.snygg)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testRuntimeOnly(libs.junit.vintage.engine)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

fun getGitCommitHash(short: Boolean = false): Provider<String> {
    if (!File(".git").exists()) {
        return providers.provider { "null" }
    }

    val execProvider = providers.exec {
        if (short) {
            commandLine("git", "rev-parse", "--short", "HEAD")
        } else {
            commandLine("git", "rev-parse", "HEAD")
        }
    }
    return execProvider.standardOutput.asText.map { it.trim() }
}

fun Project.resolveSecureEndpoint(
    propertyName: String,
    envName: String,
    fallbackValue: String? = null,
    requireHttps: Boolean = false,
    disallowPlaceholder: Boolean = false,
): String {
    val configuredValue = providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(envName))
        .orNull
    val value = configuredValue ?: fallbackValue
        ?: throw GradleException("Missing required secure endpoint '$envName' (or Gradle property '$propertyName').")
    val shouldEnforcePlaceholderCheck = gradle.startParameter.taskNames.any { taskName ->
        taskName.contains("release", ignoreCase = true) ||
            taskName.contains("beta", ignoreCase = true) ||
            taskName.contains("benchmark", ignoreCase = true)
    }

    if (requireHttps && !value.startsWith("https://")) {
        throw GradleException("Secure endpoint '$envName' must use HTTPS: $value")
    }
    if (
        disallowPlaceholder &&
        shouldEnforcePlaceholderCheck &&
        (configuredValue == null || value.contains("example.com") || value.contains(".invalid"))
    ) {
        throw GradleException("Secure endpoint '$envName' must be configured for this environment.")
    }

    return value
}

fun quoted(value: String): String = "\"$value\""

/**
 * Redirect URI sent to Google and registered in Google Cloud Console.
 * Always https (non-loopback): Google OAuth policy rejects http redirect_uri except localhost/127.0.0.1.
 * Google also rejects **raw IP** hosts in redirect_uri; use a DNS name via [resolveSecureOAuthRedirectUri].
 *
 * Optional override (same value must be registered in Google Cloud Console):
 * - Environment variable `SECURE_OAUTH_REDIRECT_URI`
 * - Gradle property `secureOauthRedirectUri`
 */
fun Project.resolveSecureOAuthRedirectUri(apiBaseUrl: String): String {
    val override = providers.environmentVariable("SECURE_OAUTH_REDIRECT_URI")
        .orElse(providers.gradleProperty("secureOauthRedirectUri"))
        .orNull
        ?.trim()
    if (!override.isNullOrEmpty()) {
        return override
    }
    return buildDefaultSecureOAuthRedirectUri(apiBaseUrl)
}

fun Project.manifestOAuthHostFromRedirect(oauthRedirectUri: String): String {
    return URI(oauthRedirectUri).host
        ?: throw GradleException("SECURE_OAUTH_REDIRECT_URI must include a host: $oauthRedirectUri")
}

private fun Project.buildDefaultSecureOAuthRedirectUri(apiBaseUrl: String): String {
    val parsed = URI(apiBaseUrl)
    val host = parsed.host ?: throw GradleException("Secure API base URL must include a host: $apiBaseUrl")
    val portSuffix = if (parsed.port != -1) ":${parsed.port}" else ""
    return "https://$host$portSuffix/oauth/callback/android"
}
