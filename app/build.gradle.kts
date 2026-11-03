import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// The release version and the backend contract revision live in one file. The
// versionCode is derived from the version, so no number is ever typed twice.
// See docs/versioning.md.
val versionProperties =
    Properties().also { properties ->
      val file = project.file("version.properties")
      require(file.isFile) { "app/version.properties is missing" }
      file.inputStream().use(properties::load)
    }

val appVersion = versionProperties.getProperty("version")?.trim().orEmpty()
val appVersionParts =
    appVersion
        .split('.')
        .also { parts ->
          require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
            "app/version.properties: version must be MAJOR.MINOR.PATCH, got '$appVersion'"
          }
        }
        .map(String::toInt)

// Three digits per part keep the derived code unique and increasing:
// 0.2.0 -> 2000, 1.0.0 -> 1000000. The check runs for every task, so an
// out-of-range version fails the build instead of silently colliding.
require(appVersionParts.all { it in 0..999 }) {
  "app/version.properties: every part of '$appVersion' must be between 0 and 999"
}

val appVersionCode = appVersionParts[0] * 1_000_000 + appVersionParts[1] * 1_000 + appVersionParts[2]

val requiredApiVersion: Int =
    versionProperties.getProperty("requires_api")?.trim()?.toIntOrNull()
        ?: error("app/version.properties: requires_api must be an integer")

// The commit hash is build provenance for the diagnostics log; a source archive
// without git says "unknown" instead of failing the build.
fun gitShortCommit(): String =
    runCatching {
          providers
              .exec { commandLine("git", "rev-parse", "--short", "HEAD") }
              .standardOutput
              .asText
              .get()
              .trim()
        }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: "unknown"

// Release signing comes from the environment in CI, or from a gitignored
// keystore.properties locally. Without either, the release APK stays unsigned
// so unit tests and debug builds never need the key.
val signingProperties =
    Properties().also { properties ->
      val file = rootProject.file("keystore.properties")
      if (file.isFile) file.inputStream().use(properties::load)
    }

fun signingValue(property: String, environment: String): String? =
    System.getenv(environment)?.takeIf { it.isNotBlank() }
        ?: signingProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "TAIGA_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "TAIGA_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "TAIGA_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "TAIGA_KEY_PASSWORD")
val hasReleaseSigning =
    listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all {
      it != null
    }

android {
  namespace = "com.rbmr.taigasleep"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.rbmr.taigasleep"
    minSdk = 31
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersion
    buildConfigField("String", "GIT_COMMIT", "\"${gitShortCommit()}\"")
    buildConfigField("int", "API_VERSION", requiredApiVersion.toString())
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(releaseStoreFile!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    debug {
      // A different package name lets a test build and the real app coexist.
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
    release {
      // Minification waits until a release APK has been exercised on real hardware.
      isMinifyEnabled = false
      isShrinkResources = false
      if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions { unitTests.isReturnDefaultValues = true }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.navigation.compose)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)

  implementation(libs.polar.ble.sdk)
  implementation(libs.rxjava)
  implementation(libs.rxandroid)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.androidx.work.runtime.ktx)

  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  // The android.jar stub of org.json does nothing in JVM tests.
  testImplementation(libs.org.json)
  testImplementation(libs.okhttp.mockwebserver)
}
