package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.components.TaigaSheet
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * Settings → About → Open-source licences: every library the app bundles, grouped by the licence
 * it is used under. The list is generated from the dependencies at build time, so it cannot drift
 * from what the app actually contains.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val catalogue = remember { context.licenceCatalogue() }
  var shownLicence by remember { mutableStateOf<Licence?>(null) }
  val space = MaterialTheme.taiga.space

  TaigaScreen(
      title = stringResource(R.string.licences_title),
      onBack = onBack,
      scrollable = false,
  ) {
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.m),
    ) {
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.licences_intro),
              style = MaterialTheme.taiga.text.body,
          )
        }
      }
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.about_affiliation),
              style = MaterialTheme.taiga.text.body,
          )
        }
      }
      catalogue.groups.forEach { group ->
        item(key = group.licence.name) {
          val licence = group.licence
          LicenceSection(
              group = group,
              onRead =
                  if (licence.content.isNullOrBlank()) null
                  else {
                    { shownLicence = licence }
                  },
          )
        }
      }
    }
  }

  shownLicence?.let { licence ->
    LicenceSheet(licence = licence, onDismiss = { shownLicence = null })
  }
}

/** One licence and the libraries used under it. */
@Composable
private fun LicenceSection(group: LicenceGroup, onRead: (() -> Unit)?) {
  Section(title = group.licence.name.ifEmpty { stringResource(R.string.licences_unknown) }) {
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      if (onRead != null) {
        ListRow(title = stringResource(R.string.licences_read), onClick = onRead)
        SettingsDivider()
      }
      group.libraries.forEachIndexed { index, library ->
        if (index > 0) SettingsDivider()
        ListRow(
            title = library.name,
            subtitle = library.description?.let(::oneLine),
            value = library.artifactVersion,
            trailing = ListRowTrailing.None,
        )
      }
    }
  }
}

/** The licence text itself, in the one sheet of the app. */
@Composable
private fun LicenceSheet(licence: Licence, onDismiss: () -> Unit) {
  val space = MaterialTheme.taiga.space
  TaigaSheet(onDismiss = onDismiss) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = space.m).padding(bottom = space.l),
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
      Text(text = licence.name, style = MaterialTheme.taiga.text.section)
      licence.url?.let { url ->
        Text(
            text = url,
            style = MaterialTheme.taiga.text.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      licence.content?.let { content ->
        Text(text = content, style = MaterialTheme.taiga.text.body)
      }
    }
  }
}

/** POM descriptions come with the author's line breaks and indentation. A row shows one run. */
private fun oneLine(text: String): String = text.replace(Whitespace, " ").trim()

private val Whitespace = Regex("\\s+")
