package com.rbmr.taigasleep.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the licences screen equal to the app. The metadata it reads is generated from the release
 * dependencies when the app is built, so this test fails when a library whose code is in the app is
 * missing from the screen, and when the Polar BLE SDK entry loses the notice its licence requires.
 */
class LicenceCatalogueTest {
  @Test
  fun `every library with code in the app is in the list`() {
    val listed = catalogue().libraries.map { it.uniqueId }.toSet()
    val missing = bundled().filterNot { it in listed }.sorted()

    assertTrue(
        "Libraries in the release app but not in the licence metadata:\n" +
            missing.joinToString("\n"),
        missing.isEmpty(),
    )
  }

  @Test
  fun `every library declares a licence`() {
    val undeclared = catalogue().libraries.filter { it.licenses.isEmpty() }.map { it.uniqueId }

    assertEquals("Libraries without a licence: $undeclared", emptyList<String>(), undeclared)
  }

  @Test
  fun `the Polar BLE SDK entry carries its notice`() {
    val catalogue = catalogue()
    val polar =
        catalogue.libraries.singleOrNull {
          it.uniqueId == "com.github.polarofficial:polar-ble-sdk"
        }
    assertNotNull("the Polar BLE SDK is not in the licence metadata", polar)

    val description = polar!!.description.orEmpty()
    assertTrue(
        "the entry does not name the copyright holder: $description",
        "Copyright Polar Electro Oy" in description,
    )

    val licence = catalogue.licenceOf(polar)
    assertNotNull("the entry has no licence", licence)
    val text = licence!!.content.orEmpty()
    assertTrue("the licence has no text", text.isNotBlank())
    assertTrue("the licence is not the Polar SDK licence: ${licence.name}", "Polar" in licence.name)
    assertTrue(
        "the licence text is not the Polar SDK agreement",
        "LIMITED LICENSE AGREEMENT" in text,
    )
    assertTrue("the licence text has no copyright notice", "Copyright (c) Polar Electro Oy" in text)
  }

  /** The metadata generated for the release variant. */
  private fun catalogue(): LicenceCatalogue =
      parseLicenceCatalogue(generated("taiga.licences.metadata", MetadataPath).readText())

  /** The coordinates of the libraries whose code the release variant puts in the app. */
  private fun bundled(): List<String> =
      generated("taiga.licences.dependencies", DependenciesPath)
          .readLines()
          .map { it.trim() }
          .filter { it.isNotEmpty() }

  private fun generated(property: String, path: String): File {
    val file =
        System.getProperty(property)?.takeIf { it.isNotBlank() }?.let(::File)
            ?: listOf(File("."), File("app")).map { File(it, path) }.firstOrNull { it.isFile }
            ?: File(path)
    assertTrue("$file is missing; run ./gradlew :app:testDebugUnitTest", file.isFile)
    return file
  }

  private companion object {
    const val MetadataPath = "build/generated/aboutLibraries/release/res/raw/aboutlibraries.json"
    const val DependenciesPath = "build/generated/licences/release-runtime-dependencies.txt"
  }
}
