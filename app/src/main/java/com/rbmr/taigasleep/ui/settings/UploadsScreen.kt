package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.sync.RawRetentionMode
import com.rbmr.taigasleep.sync.UploadSettings
import com.rbmr.taigasleep.ui.components.ChoiceChip
import com.rbmr.taigasleep.ui.components.InfoContent
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga
import kotlinx.coroutines.launch

/** The retention choices in the order they read: delete the raw data, or keep all of it. */
private val RetentionChoices =
    listOf(
        RawRetentionMode.IMMEDIATE,
        RawRetentionMode.DAYS_5,
        RawRetentionMode.DAYS_14,
        RawRetentionMode.DAYS_30,
        RawRetentionMode.NEVER,
    )

/** Settings → Uploads: whether recordings go by themselves, how they are sent and what stays. */
@Composable
fun UploadsScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val uploadSettings = remember { UploadSettings.get(context) }
  val autoUpload by uploadSettings.autoUpload.collectAsStateWithLifecycle()
  val compressUploads by uploadSettings.compressUploads.collectAsStateWithLifecycle()
  val rawRetention by uploadSettings.rawRetention.collectAsStateWithLifecycle()
  val sync = remember { context.appContainer.syncService }
  val scope = rememberCoroutineScope()
  val space = MaterialTheme.taiga.space

  TaigaScreen(title = stringResource(R.string.settings_uploads_title), onBack = onBack) {
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      ListRow(
          title = stringResource(R.string.settings_auto_upload_title),
          trailing = ListRowTrailing.Switch(autoUpload.enabled),
          onClick = {
            uploadSettings.setEnabled(!autoUpload.enabled)
            sync.rescheduleAll()
          },
      )
      if (autoUpload.enabled) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = space.m, end = space.m, bottom = space.s),
            horizontalArrangement = Arrangement.spacedBy(space.s),
        ) {
          ChoiceChip(
              label = stringResource(R.string.settings_auto_upload_wifi),
              selected = autoUpload.wifiOnly,
              onClick = {
                uploadSettings.setWifiOnly(!autoUpload.wifiOnly)
                sync.rescheduleAll()
              },
              role = Role.Checkbox,
          )
          ChoiceChip(
              label = stringResource(R.string.settings_auto_upload_charging),
              selected = autoUpload.chargingOnly,
              onClick = {
                uploadSettings.setChargingOnly(!autoUpload.chargingOnly)
                sync.rescheduleAll()
              },
              role = Role.Checkbox,
          )
        }
      }
      SettingsDivider()
      ListRow(
          title = stringResource(R.string.settings_upload_compress_title),
          trailing = ListRowTrailing.Switch(compressUploads),
          onClick = { uploadSettings.setCompressUploads(!compressUploads) },
      )
    }
    Section(
        title = stringResource(R.string.uploads_raw_title),
        info =
            InfoContent(
                title = stringResource(R.string.uploads_raw_title),
                body = stringResource(R.string.uploads_raw_body),
            ),
    ) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        RetentionChoices.forEachIndexed { index, mode ->
          if (index > 0) SettingsDivider()
          ListRow(
              title = rawRetentionLabel(mode),
              trailing = ListRowTrailing.Radio(rawRetention == mode),
              onClick = {
                uploadSettings.setRawRetention(mode)
                scope.launch { sync.enforceRawRetention() }
              },
          )
        }
      }
    }
  }
}

@Composable
private fun rawRetentionLabel(mode: RawRetentionMode): String =
    stringResource(
        when (mode) {
          RawRetentionMode.NEVER -> R.string.raw_retention_keep_all
          RawRetentionMode.IMMEDIATE -> R.string.raw_retention_keep_none
          RawRetentionMode.DAYS_5 -> R.string.raw_retention_days_5
          RawRetentionMode.DAYS_14 -> R.string.raw_retention_days_14
          RawRetentionMode.DAYS_30 -> R.string.raw_retention_days_30
        }
    )
