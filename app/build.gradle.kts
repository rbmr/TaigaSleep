plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "com.rbmr.taigasleep"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.rbmr.taigasleep"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
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

  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  // The android.jar stub of org.json does nothing in JVM tests.
  testImplementation(libs.org.json)
  testImplementation(libs.okhttp.mockwebserver)
}
