import java.io.File
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult

plugins {
  alias(libs.plugins.aboutlibraries)
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
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

// Robolectric loads a real Android framework jar for the SDK level of a test class and would
// download it from Maven Central while the tests run: outside Gradle, without its retries and
// without sharing its cache, so a single failed request fails the whole test job. The jars for
// the SDK levels the tests select are resolved through Gradle instead, copied into one directory
// and handed to Robolectric in offline mode beside the other unit test settings below. See
// docs/ci.md.
val robolectricSdkJars =
    mapOf(
        34 to libs.android.all.instrumented.sdk34,
        36 to libs.android.all.instrumented.sdk36,
    )
val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdks")
// Two versions of one module conflict inside a single configuration, so each jar gets its own.
val robolectricSdkConfigurations =
    robolectricSdkJars.map { (sdk, jar) ->
      configurations
          .create("robolectricSdk$sdk") {
            isCanBeConsumed = false
            isCanBeResolved = true
            isTransitive = false
          }
          .also { configuration -> dependencies.add(configuration.name, jar) }
    }

val prepareRobolectricSdkJars =
    tasks.register<Sync>("prepareRobolectricSdkJars") {
      description = "Copies the Android jars Robolectric runs the tests on, for offline use."
      robolectricSdkConfigurations.forEach { from(it) }
      into(robolectricSdkDirectory)
    }

android {
  namespace = "com.rbmr.taigasleep"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.rbmr.taigasleep"
    minSdk = 31
    targetSdk = 36
    versionCode = appVersionCode
    versionName = appVersion
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
      // A fixed value keeps the debug build up to date across commits: the hash is provenance for
      // what was released, and a debug build is not that.
      buildConfigField("String", "GIT_COMMIT", "\"debug\"")
    }
    release {
      // The hash is part of the release's provenance, so it changes with every commit it ships.
      buildConfigField("String", "GIT_COMMIT", "\"${gitShortCommit()}\"")
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  testOptions {
    // Robolectric tests read the app's real resources and manifest, so what they assert is what
    // the phone shows.
    unitTests.isIncludeAndroidResources = true
    unitTests.isReturnDefaultValues = true
    unitTests.all { test ->
      // The opt-in export benchmark reads this; a test JVM does not inherit the build's properties.
      System.getProperty("taiga.benchmark")?.let { test.systemProperty("taiga.benchmark", it) }
      // Robolectric would download its Android jar from Maven Central while the tests run; offline
      // mode reads the copies that Gradle resolved itself instead, so the network cannot fail the
      // run. See docs/ci.md.
      test.dependsOn(prepareRobolectricSdkJars)
      test.systemProperty("robolectric.offline", "true")
      test.systemProperty(
          "robolectric.dependency.dir",
          robolectricSdkDirectory.get().asFile.absolutePath,
      )
      // The jars are an input like any other: a new version reruns the tests instead of reusing a
      // cached result from the old one.
      test.inputs.files(robolectricSdkConfigurations).withPropertyName("robolectricSdkJars")
      // Robolectric emulates the current Android release and reaches into this JDK-internal package
      // while it sets the application up.
      test.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
      // The tests are independent per class and a JVM is their isolation boundary: no class shares
      // process state with another, so several JVMs can run beside each other. Four is the CI
      // runner's core count; a machine with fewer cores uses fewer. See docs/ci.md.
      test.maxParallelForks = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
  }

  lint {
    baseline = file("lint-baseline.xml")
    abortOnError = true
    // A resource nothing shows is a resource nothing needs: a new unused one fails the build.
    error += "UnusedResources"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

// Room writes the schema of every version next to the sources, so a later change to the database is
// reviewed as a diff of the generated schema and migrations are written against what shipped.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// The licences screen reads the dependency metadata the AboutLibraries plugin generates for the
// release variant, so the list cannot go stale. Offline mode is required: the generated file must
// describe what is bundled without downloading anything at build time, so the licence texts ship
// in app/licenses and are merged in by the plugin.
aboutLibraries {
  offlineMode = true
  collect {
    includePlatform = false
    configPath = project.file("licenses")
  }
}

/**
 * Writes the coordinates of the libraries whose code the release variant puts in the app. The
 * licence screen reads the metadata the AboutLibraries plugin generates; a unit test compares that
 * metadata with this list and fails when a bundled library is missing from the screen.
 */
abstract class WriteRuntimeDependencies : DefaultTask() {
  @get:Input abstract val coordinates: ListProperty<String>

  @get:OutputFile abstract val outputFile: RegularFileProperty

  @TaskAction
  fun write() {
    val lines = coordinates.get().distinct().sorted()
    outputFile.get().asFile
        .apply { parentFile.mkdirs() }
        .writeText(lines.joinToString("\n", postfix = "\n"))
  }
}

/**
 * The coordinate of an artifact that carries compiled classes. A publication that only redirects
 * to the module holding the code (a Kotlin multiplatform root, for example) adds no code of its own
 * and is not a library the screens have to list.
 */
fun ResolvedArtifactResult.codeCoordinate(): String? {
  val id = id.componentIdentifier
  if (id !is ModuleComponentIdentifier || id.group.isEmpty()) return null
  return if (file.containsClasses()) "${id.group}:${id.module}" else null
}

fun File.containsClasses(): Boolean {
  val zip =
      try {
        ZipFile(this)
      } catch (_: Exception) {
        return false
      }
  zip.use {
    for (entry in it.entries()) {
      if (entry.name.endsWith(".class")) return true
      if (entry.name == "classes.jar") {
        ZipInputStream(it.getInputStream(entry)).use { classes ->
          while (true) {
            val next = classes.nextEntry ?: break
            if (next.name.endsWith(".class")) return true
          }
        }
      }
    }
  }
  return false
}

// The variant's configuration is created late in the configuration phase, so it is looked up when
// the task runs rather than while this script is evaluated.
val releaseRuntimeCoordinates =
    provider {
      configurations
          .getByName("releaseRuntimeClasspath")
          .incoming
          .artifactView { lenient(true) }
          .artifacts
          .artifacts
          .mapNotNull { it.codeCoordinate() }
          .toSet()
    }

val writeLicenceDependencies =
    tasks.register<WriteRuntimeDependencies>("writeLicenceDependencies") {
      description = "Writes the release runtime dependencies the licence screen has to list."
      coordinates.set(releaseRuntimeCoordinates)
      outputFile.set(
          layout.buildDirectory.file("generated/licences/release-runtime-dependencies.txt")
      )
    }

val licenceMetadataFile =
    layout.buildDirectory.file("generated/aboutLibraries/release/res/raw/aboutlibraries.json")
val licenceDependenciesFile =
    layout.buildDirectory.file("generated/licences/release-runtime-dependencies.txt")

tasks.withType<Test>().configureEach {
  dependsOn("prepareLibraryDefinitionsRelease", writeLicenceDependencies)
  // The two generated files are inputs, so a change in the app's dependencies reruns the test.
  inputs.file(licenceMetadataFile).withPropertyName("licenceMetadata")
  inputs.file(licenceDependenciesFile).withPropertyName("licenceDependencies")
  // These tests read files outside the classpath, so they are inputs too: the build cache may only
  // hand back a result when the files the test checked are the same.
  inputs.file(rootProject.file("docs/architecture.md")).withPropertyName("architectureDocument")
  inputs.file(rootProject.file("tools/read_diagnostics.py")).withPropertyName("diagnosticsReader")
  // SimulatedRecordingTest runs tools/read_recording.py, which imports the worker's frame module.
  inputs.file(rootProject.file("tools/read_recording.py")).withPropertyName("recordingReader")
  inputs
      .file(rootProject.file("backend/worker/taiga_worker/frames.py"))
      .withPropertyName("workerFrameModule")
  systemProperty("taiga.licences.metadata", licenceMetadataFile.get().asFile.absolutePath)
  systemProperty("taiga.licences.dependencies", licenceDependenciesFile.get().asFile.absolutePath)
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)

  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)

  implementation(libs.polar.ble.sdk)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)

  debugImplementation(libs.androidx.compose.ui.tooling)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  // The screens are tested as they are drawn, on the JVM through Robolectric.
  testImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  // Room needs the Android SQLite stack; Robolectric provides it in JVM tests.
  testImplementation(libs.robolectric)
}
