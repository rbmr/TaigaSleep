package com.rbmr.taigasleep.ui.settings

import com.rbmr.taigasleep.ui.components.joinParts
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
import com.rbmr.taigasleep.ui.components.ChoiceButton
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
import com.rbmr.taigasleep.ui.components.TaigaSheet
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * Settings → Server → Access tokens: make a read-only token for an assistant or a script, copy it
 * once, see the account's tokens and revoke them. A token may be made to stop working after a
 * lifetime; the list says when each one ends.
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
  var lifetime by rememberSaveable { mutableStateOf(AccessTokenLifetime.NEVER) }
  var choosingLifetime by remember { mutableStateOf(false) }
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
          ChoiceButton(
              label =
                  stringResource(
                      R.string.tokens_expiry_value,
                      stringResource(lifetimeLabel(lifetime)),
                  ),
              action = stringResource(R.string.tokens_expiry_change),
              onClick = { choosingLifetime = true },
          )
          PrimaryButton(
              text = stringResource(R.string.tokens_create),
              onClick = { viewModel.create(name, lifetime) },
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
                modifier = Modifier.padding(MaterialTheme.taiga.space.m),
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

  if (choosingLifetime) {
    TaigaSheet(onDismiss = { choosingLifetime = false }) {
      Column(
          modifier =
              Modifier.fillMaxWidth().padding(bottom = MaterialTheme.taiga.space.l),
      ) {
        Text(
            text = stringResource(R.string.tokens_expiry_title),
            style = MaterialTheme.taiga.text.section,
            modifier = Modifier.padding(horizontal = MaterialTheme.taiga.space.m),
        )
        AccessTokenLifetime.entries.forEach { option ->
          ListRow(
              title = stringResource(lifetimeLabel(option)),
              trailing = ListRowTrailing.Radio(option == lifetime),
              onClick = {
                lifetime = option
                choosingLifetime = false
              },
          )
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
      subtitle =
          joinParts(
              token.hint.takeIf { it.isNotBlank() }?.let { stringResource(R.string.tokens_row_ends, it) },
              dateLabel(token.created)
                  .takeIf { it.isNotBlank() }
                  ?.let { stringResource(R.string.tokens_row_created, it) },
              expiryLabel(token),
          ),
      trailing =
          ListRowTrailing.Action(
              label = stringResource(R.string.tokens_revoke),
              onClick = onRevoke,
              destructive = true,
          ),
  )
}

/** What the row says about the token's expiry: when it ends, that it has ended, or that it has none. */
@Composable
private fun expiryLabel(token: AccessTokenDto): String? =
    when (expiryKind(token.expires_at, token.expired)) {
      TokenExpiry.NEVER -> stringResource(R.string.tokens_row_never)
      TokenExpiry.ACTIVE ->
          expiryDate(token.expires_at)?.let { stringResource(R.string.tokens_row_expires, it) }
      TokenExpiry.EXPIRED ->
          expiryDate(token.expires_at)?.let { stringResource(R.string.tokens_row_expired, it) }
    }

/** The name of a lifetime, as the choice and the choice's button say it. */
private fun lifetimeLabel(lifetime: AccessTokenLifetime): Int =
    when (lifetime) {
      AccessTokenLifetime.DAYS_7 -> R.string.tokens_expiry_7d
      AccessTokenLifetime.DAYS_30 -> R.string.tokens_expiry_30d
      AccessTokenLifetime.DAYS_90 -> R.string.tokens_expiry_90d
      AccessTokenLifetime.YEAR -> R.string.tokens_expiry_year
      AccessTokenLifetime.NEVER -> R.string.tokens_expiry_never
    }

