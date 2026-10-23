package com.rbmr.taigasleep.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.BuildConfig
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.components.SettingsRow
import com.rbmr.taigasleep.ui.components.TaigaCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val uriHandler = LocalUriHandler.current
  val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val mark = if (dark) R.drawable.taiga_mark_white else R.drawable.taiga_mark_black

  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.about_title)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.about_back),
                )
              }
            },
        )
      },
  ) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      item {
        Image(
            painter = painterResource(mark),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.fillMaxWidth(0.55f).height(120.dp),
            contentScale = ContentScale.Fit,
        )
      }
      item {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            textAlign = TextAlign.Center,
        )
      }
      item {
        Text(
            text =
                stringResource(
                    R.string.about_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
      }
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.about_description),
              style = MaterialTheme.typography.bodyLarge,
          )
        }
      }
      item {
        SettingsRow(
            icon = Icons.Outlined.Code,
            title = stringResource(R.string.about_source_title),
            subtitle = stringResource(R.string.about_source_subtitle),
            onClick = { uriHandler.openUri("https://github.com/rbmr/TaigaSleep") },
        )
      }
      item {
        SettingsRow(
            icon = Icons.Outlined.Description,
            title = stringResource(R.string.about_license_title),
            subtitle = stringResource(R.string.about_license_subtitle),
            onClick = {
              uriHandler.openUri("https://github.com/rbmr/TaigaSleep/blob/main/LICENSE")
            },
        )
      }
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.about_privacy_title),
              style = MaterialTheme.typography.titleLarge,
          )
          Spacer(Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.about_privacy_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      item {
        TaigaCard {
          Text(
              text = stringResource(R.string.about_built_title),
              style = MaterialTheme.typography.titleLarge,
          )
          Spacer(Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.about_built_body),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      item {
        Text(
            text = stringResource(R.string.about_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
      }
    }
  }
}
