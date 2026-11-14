package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaLogo
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga

/** Settings → About: what the app is, where its code is and what it is built on. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val uriHandler = LocalUriHandler.current
  val space = MaterialTheme.taiga.space

  TaigaScreen(title = stringResource(R.string.about_title), onBack = onBack) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(space.s),
    ) {
      TaigaLogo(
          modifier = Modifier.width(190.dp),
          contentDescription = stringResource(R.string.app_name),
      )
      Text(text = stringResource(R.string.app_name), style = MaterialTheme.taiga.text.section)
      Text(
          text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
          style = MaterialTheme.taiga.text.meta,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    TaigaCard {
      Text(
          text = stringResource(R.string.about_description),
          style = MaterialTheme.taiga.text.body,
      )
    }
    Section(title = stringResource(R.string.about_group_project)) {
      TaigaCard(contentPadding = PaddingValues(0.dp)) {
        ListRow(
            title = stringResource(R.string.about_source_title),
            subtitle = stringResource(R.string.about_source_subtitle),
            onClick = { uriHandler.openUri("https://github.com/rbmr/TaigaSleep") },
        )
        SettingsDivider()
        ListRow(
            title = stringResource(R.string.about_license_title),
            subtitle = stringResource(R.string.about_license_subtitle),
            onClick = {
              uriHandler.openUri("https://github.com/rbmr/TaigaSleep/blob/main/LICENSE")
            },
        )
      }
    }
    TaigaCard {
      Text(text = stringResource(R.string.about_privacy_title), style = MaterialTheme.taiga.text.row)
      Spacer(Modifier.height(space.s))
      Text(
          text = stringResource(R.string.about_privacy_body),
          style = MaterialTheme.taiga.text.body,
      )
    }
    TaigaCard {
      Text(text = stringResource(R.string.about_built_title), style = MaterialTheme.taiga.text.row)
      Spacer(Modifier.height(space.s))
      Text(
          text = stringResource(R.string.about_built_body),
          style = MaterialTheme.taiga.text.body,
      )
    }
    Text(
        text = stringResource(R.string.about_disclaimer),
        style = MaterialTheme.taiga.text.meta,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}
