package com.rbmr.taigasleep.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.rbmr.taigasleep.ui.components.ConfirmDialog
import com.rbmr.taigasleep.ui.components.ConfirmDialogStyle
import com.rbmr.taigasleep.ui.components.ListRow
import com.rbmr.taigasleep.ui.components.ListRowTrailing
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.taiga
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Settings → Server → Access tokens: make a read-only token for an assistant or a script, copy it
 * once, see the account's tokens and revoke them.
 */
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

  TaigaScreen(
      title = stringResource(R.string.tokens_title),
      subtitle = stringResource(R.string.tokens_body),
      onBack = onBack,
  ) {
    if (!state.signedIn) {
      TaigaCard {
        Text(
            text = stringResource(R.string.tokens_no_account),
            style = MaterialTheme.taiga.text.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      TaigaCard {
        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.m)) {
          OutlinedTextField(
              value = name,
              onValueChange = { name = it },
              modifier = Modifier.fillMaxWidth(),
              label = { Text(stringResource(R.string.tokens_name_label)) },
              singleLine = true,
              shape = MaterialTheme.taiga.shape.control,
          )
          PrimaryButton(
              text = stringResource(R.string.tokens_create),
              onClick = { viewModel.create(name) },
              enabled = name.isNotBlank() && !state.busy,
              size = ButtonSize.Large,
          )
        }
      }
      state.error?.let { failure ->
        StatusBanner(kind = StatusKind.Bad, body = failure)
      }
      Section(title = stringResource(R.string.tokens_group)) {
        TaigaCard(contentPadding = PaddingValues(0.dp)) {
          if (state.tokens.isEmpty()) {
            Text(
                text =
                    stringResource(
                        if (state.loading) R.string.tokens_loading else R.string.tokens_empty
                    ),
                style = MaterialTheme.taiga.text.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
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

  state.created?.let { secret ->
    AlertDialog(
        onDismissRequest = {
          viewModel.dismissCreated()
          copied = false
        },
        title = { Text(text = stringResource(R.string.tokens_created_title), style = MaterialTheme.taiga.text.section) },
        text = {
          Column {
            Text(
                text = stringResource(R.string.tokens_created_body),
                style = MaterialTheme.taiga.text.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MaterialTheme.taiga.space.m))
            Text(
                text = secret,
                style = MaterialTheme.taiga.text.body.copy(fontFamily = FontFamily.Monospace),
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MaterialTheme.taiga.space.m))
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
            Text(text = stringResource(R.string.tokens_done))
          }
        },
    )
  }

  revoking?.let { token ->
    ConfirmDialog(
        title = stringResource(R.string.tokens_revoke_title),
        body = stringResource(R.string.tokens_revoke_body, token.name),
        confirmLabel = stringResource(R.string.tokens_revoke),
        onConfirm = {
          viewModel.revoke(token.id)
          revoking = null
        },
        onDismiss = { revoking = null },
        style = ConfirmDialogStyle.Destructive,
    )
  }
}

@Composable
private fun TokenRow(token: AccessTokenDto, onRevoke: () -> Unit) {
  ListRow(
      title = token.name,
      subtitle = stringResource(R.string.tokens_row, token.hint, createdLabel(token.created)),
      trailing =
          ListRowTrailing.Action(
              label = stringResource(R.string.tokens_revoke),
              onClick = onRevoke,
              destructive = true,
          ),
  )
}

/** The creation time in the phone's zone, or the stamp itself when it cannot be read. */
private fun createdLabel(stamp: String): String =
    runCatching {
          OffsetDateTime.parse(stamp)
              .atZoneSameInstant(ZoneId.systemDefault())
              .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        }
        .getOrDefault(stamp)
