package com.rbmr.taigasleep.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AccessTokenDto
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.SettingsGroup
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings → Server → Access tokens: make a read-only token for an assistant or a script, copy it
 * once, see the account's tokens and revoke them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessTokensScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val factory = remember { AccessTokensViewModel.Factory(context) }
  val viewModel: AccessTokensViewModel = viewModel(factory = factory)
  val state by viewModel.state.collectAsStateWithLifecycle()
  val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
  var name by rememberSaveable { mutableStateOf("") }
  var revoking by remember { mutableStateOf<AccessTokenDto?>(null) }
  var copied by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) { viewModel.refresh() }
  // The name field only empties once the token exists, so a failed create keeps what was typed.
  LaunchedEffect(state.created) {
    if (state.created != null) name = ""
  }

  fun copy(token: String) {
    clipboard.setPrimaryClip(ClipData.newPlainText("taiga-access-token", token))
    copied = true
  }

  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.tokens_title)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.server_back),
                )
              }
            },
        )
      },
  ) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      item {
        ScreenHeader(
            title = stringResource(R.string.tokens_title),
            subtitle = stringResource(R.string.tokens_body),
        )
      }
      if (!state.signedIn) {
        item {
          TaigaCard {
            Text(
                text = stringResource(R.string.tokens_no_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        item {
          TaigaCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              OutlinedTextField(
                  value = name,
                  onValueChange = { name = it },
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text(stringResource(R.string.tokens_name_label)) },
                  singleLine = true,
                  shape = RoundedCornerShape(16.dp),
              )
              PrimaryButton(
                  text = stringResource(R.string.tokens_create),
                  onClick = { viewModel.create(name) },
                  enabled = name.isNotBlank() && !state.busy,
                  size = ButtonSize.Large,
              )
            }
          }
        }
        state.error?.let { failure ->
          item {
            Text(
                text = failure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.taiga.bad,
            )
          }
        }
        item {
          SettingsGroup(stringResource(R.string.tokens_group)) {
            if (state.tokens.isEmpty()) {
              Text(
                  text =
                      stringResource(
                          if (state.loading) R.string.tokens_loading else R.string.tokens_empty
                      ),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
              )
            } else {
              state.tokens.forEachIndexed { index, token ->
                if (index > 0) SettingsDivider()
                TokenRow(token = token, onRevoke = { revoking = token })
              }
            }
          }
        }
      }
    }
  }

  state.created?.let { secret ->
    AlertDialog(
        onDismissRequest = {
          viewModel.dismissCreated()
          copied = false
        },
        title = { Text(stringResource(R.string.tokens_created_title)) },
        text = {
          Column {
            Text(
                text = stringResource(R.string.tokens_created_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = secret,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                text =
                    stringResource(
                        if (copied) R.string.tokens_copied else R.string.tokens_copy
                    ),
                onClick = { copy(secret) },
                modifier = Modifier.fillMaxWidth(),
            )
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                viewModel.dismissCreated()
                copied = false
              }
          ) {
            Text(stringResource(R.string.tokens_done))
          }
        },
    )
  }

  revoking?.let { token ->
    AlertDialog(
        onDismissRequest = { revoking = null },
        title = { Text(stringResource(R.string.tokens_revoke_title)) },
        text = { Text(stringResource(R.string.tokens_revoke_body, token.name)) },
        confirmButton = {
          TextButton(
              onClick = {
                viewModel.revoke(token.id)
                revoking = null
              }
          ) {
            Text(stringResource(R.string.tokens_revoke), color = MaterialTheme.taiga.bad)
          }
        },
        dismissButton = {
          TextButton(onClick = { revoking = null }) {
            Text(stringResource(R.string.server_cancel))
          }
        },
    )
  }
}

@Composable
private fun TokenRow(token: AccessTokenDto, onRevoke: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(token.name, style = MaterialTheme.typography.titleMedium)
      Text(
          text = stringResource(R.string.tokens_row, token.hint, createdLabel(token.created)),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    TextButton(onClick = onRevoke) {
      Text(stringResource(R.string.tokens_revoke), color = MaterialTheme.taiga.bad)
    }
  }
}

/** The creation time in the phone's zone, or the stamp itself when it cannot be read. */
private fun createdLabel(stamp: String): String =
    runCatching {
          OffsetDateTime.parse(stamp)
              .atZoneSameInstant(ZoneId.systemDefault())
              .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
        .getOrDefault(stamp)
