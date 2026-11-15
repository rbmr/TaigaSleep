package com.rbmr.taigasleep.ui.settings

import android.content.Context
import com.rbmr.taigasleep.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One licence as the generated dependency metadata describes it. */
@Serializable
data class Licence(
    val name: String = "",
    val url: String? = null,
    val content: String? = null,
)

/** One library the app bundles, with the licences it is used under. */
@Serializable
data class LicencedLibrary(
    val uniqueId: String,
    val name: String = "",
    val artifactVersion: String? = null,
    val description: String? = null,
    val website: String? = null,
    val licenses: List<String> = emptyList(),
)

/** What the AboutLibraries plugin writes for a variant. */
@Serializable
internal data class LicenceMetadata(
    val libraries: List<LicencedLibrary> = emptyList(),
    val licenses: Map<String, Licence> = emptyMap(),
)

/**
 * A licence and every library that is used under it. A blank licence name means the library
 * declares no licence.
 */
data class LicenceGroup(val licence: Licence, val libraries: List<LicencedLibrary>)

/**
 * The libraries the app bundles, grouped by the licence they are used under. The build writes it
 * from the app's dependencies, and a unit test fails when a library in the app is missing from it.
 */
data class LicenceCatalogue(val groups: List<LicenceGroup>) {
  val libraries: List<LicencedLibrary>
    get() = groups.flatMap { group -> group.libraries }

  /** The licence of a library, or null when it declares none. */
  fun licenceOf(library: LicencedLibrary): Licence? =
      groups.firstOrNull { group -> group.libraries.any { it == library } }?.licence
}

/**
 * Reads the metadata the AboutLibraries plugin generated. Licences come first, ordered by name,
 * then the libraries under each one, ordered by name, so the screen is stable between builds.
 */
fun parseLicenceCatalogue(json: String): LicenceCatalogue {
  val metadata = LicenceJson.decodeFromString<LicenceMetadata>(json)

  val byLicence = linkedMapOf<String, MutableList<LicencedLibrary>>()
  metadata.libraries.sortedBy { it.name }.forEach { library ->
    val ids = library.licenses.ifEmpty { listOf("") }
    ids.forEach { id -> byLicence.getOrPut(id) { mutableListOf() }.add(library) }
  }

  val groups =
      byLicence
          .map { (id, libraries) ->
            LicenceGroup(
                licence = metadata.licenses[id] ?: Licence(name = id),
                libraries = libraries.distinct(),
            )
          }
          .sortedWith(compareBy({ it.licence.name }, { it.libraries.first().name }))

  return LicenceCatalogue(groups)
}

/** The catalogue the app ships: the metadata generated when this build was made. */
fun Context.licenceCatalogue(): LicenceCatalogue =
    resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use {
      parseLicenceCatalogue(it.readText())
    }

/** The file has fields this screen does not show, such as funding, so unknown keys are skipped. */
private val LicenceJson = Json { ignoreUnknownKeys = true }
