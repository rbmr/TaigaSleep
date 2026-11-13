package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AlgorithmDto
import com.rbmr.taigasleep.insights.AlgorithmNames
import com.rbmr.taigasleep.insights.compareVersions
import com.rbmr.taigasleep.insights.effectiveOrder
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.ReorderableColumn
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * Settings → Analysis: whether a recording is analyzed by itself, and which method comes first.
 * The order is the server's list of installed methods, reordered in place; an unreachable server
 * leaves the saved order on screen.
 */
@Composable
fun AnalysisScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val remoteRepository = remember { context.appContainer.remoteRepository }
  val remoteStatus by remoteRepository.status.collectAsStateWithLifecycle()
  val sync = remember { context.appContainer.syncService }
  val store = remember { context.appContainer.analysisSettingsStore }
  val settings by store.settings.collectAsStateWithLifecycle()
  var installed by remember { mutableStateOf<List<AlgorithmDto>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(remoteStatus) {
    if (remoteRepository.api() == null) {
      installed = emptyList()
      error = null
      loading = false
      return@LaunchedEffect
    }
    loading = true
    error = null
    sync.installedAlgorithms()
        .onSuccess { installed = it }
        .onFailure { error = it.message }
    loading = false
  }

  // The newest version of each method is the one the order names.
  val installedById =
      remember(installed) {
        installed
            .groupBy { it.id }
            .mapValues { (_, versions) ->
              versions.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }!!
            }
      }
  val orderIds =
      remember(settings.preferenceOrder, installedById) {
        if (installedById.isEmpty()) {
          settings.preferenceOrder
        } else {
          effectiveOrder(settings.preferenceOrder, installedById.keys)
        }
      }
  fun nameOf(id: String): String =
      installedById[id]?.name?.ifBlank { AlgorithmNames.display(id) } ?: AlgorithmNames.display(id)

  // A move saves at once; ids the server did not list stay behind the ones it did.
  fun save(order: List<String>) {
    store.setPreferenceOrder(order + settings.preferenceOrder.filter { it !in orderIds })
  }

  TaigaScreen(title = stringResource(R.string.settings_analysis_title), onBack = onBack) {
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      ListRow(
          title = stringResource(R.string.settings_analysis_auto_title),
          trailing = ListRowTrailing.Switch(settings.auto),
          onClick = { store.setAuto(!settings.auto) },
      )
    }
    Section(
        title = stringResource(R.string.settings_analysis_order_title),
        info =
            InfoContent(
                title = stringResource(R.string.settings_analysis_order_title),
                body = stringResource(R.string.settings_analysis_order_body),
            ),
    ) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        if (loading) {
          Hint(stringResource(R.string.settings_analysis_order_loading))
        } else if (error != null) {
          Hint(stringResource(R.string.settings_analysis_order_error), failed = true)
        } else if (orderIds.isEmpty()) {
          Hint(stringResource(R.string.settings_analysis_order_empty))
        } else {
          ReorderableColumn(items = orderIds, key = { it }, onReorder = ::save) { id, index, handle ->
            Column {
              if (index > 0) SettingsDivider()
              ListRow(
                  title = nameOf(id),
                  trailing = ListRowTrailing.Controls { handle() },
              )
            }
          }
        }
      }
    }
  }
}

/** The state of the list when there is nothing to reorder. */
@Composable
private fun Hint(text: String, failed: Boolean = false) {
  Text(
      text = text,
      style = MaterialTheme.taiga.text.meta,
      color = if (failed) MaterialTheme.taiga.bad else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.fillMaxWidth().padding(all = MaterialTheme.taiga.space.m),
  )
}
