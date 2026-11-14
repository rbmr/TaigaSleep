package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.theme.taiga

/** How the confirm button of a [ConfirmDialog] is filled. */
enum class ConfirmDialogStyle {
  Primary,
  Neutral,
  Destructive,
}

/**
 * The one confirmation of the app: a title, one explanation, Cancel and a filled confirm button.
 * The style says what the confirm does: continue (primary), change a preference (neutral) or
 * remove something (destructive).
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    style: ConfirmDialogStyle = ConfirmDialogStyle.Primary,
) {
  val scheme = MaterialTheme.colorScheme
  val container =
      when (style) {
        ConfirmDialogStyle.Primary -> scheme.primary
        ConfirmDialogStyle.Neutral -> scheme.surfaceVariant
        ConfirmDialogStyle.Destructive -> scheme.error
      }
  val content =
      when (style) {
        ConfirmDialogStyle.Primary -> scheme.onPrimary
        ConfirmDialogStyle.Neutral -> scheme.onSurface
        ConfirmDialogStyle.Destructive -> scheme.onError
      }
  AlertDialog(
      modifier = modifier,
      onDismissRequest = onDismiss,
      title = { Text(text = title, style = MaterialTheme.taiga.text.section) },
      text = { Text(text = body, style = MaterialTheme.taiga.text.body) },
      confirmButton = {
        Pressable(
            onClick = onConfirm,
            shape = MaterialTheme.taiga.shape.control,
            role = Role.Button,
            onClickLabel = confirmLabel,
        ) {
          Box(
              modifier =
                  Modifier.heightIn(min = 40.dp)
                      .background(container, MaterialTheme.taiga.shape.control)
                      .padding(horizontal = MaterialTheme.taiga.space.l),
              contentAlignment = Alignment.Center,
          ) {
            Text(text = confirmLabel, style = MaterialTheme.taiga.text.row, color = content)
          }
        }
      },
      dismissButton = {
        Pressable(
            onClick = onDismiss,
            shape = MaterialTheme.taiga.shape.control,
            role = Role.Button,
            onClickLabel = stringResource(R.string.action_cancel),
        ) {
          Box(
              modifier =
                  Modifier.heightIn(min = 40.dp)
                      .padding(horizontal = MaterialTheme.taiga.space.m),
              contentAlignment = Alignment.Center,
          ) {
            Text(
                text = stringResource(R.string.action_cancel),
                style = MaterialTheme.taiga.text.row,
                color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      },
      shape = MaterialTheme.taiga.shape.card,
      containerColor = MaterialTheme.colorScheme.surface,
  )
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConfirmDialogPrimaryPreview() {
  PreviewTheme {
    ConfirmDialog(
        title = "Stop recording?",
        body = "The recording is saved on this phone and uploaded in the background.",
        confirmLabel = "Stop",
        onConfirm = {},
        onDismiss = {},
    )
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConfirmDialogNeutralPreview() {
  PreviewTheme {
    ConfirmDialog(
        title = "Log out of the server?",
        body = "Taiga logs out on this phone only. Recordings stay on the phone.",
        confirmLabel = "Log out",
        onConfirm = {},
        onDismiss = {},
        style = ConfirmDialogStyle.Neutral,
    )
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConfirmDialogDestructivePreview() {
  PreviewTheme {
    ConfirmDialog(
        title = "Delete this recording?",
        body = "This removes the recording and its analysis from this phone and the server.",
        confirmLabel = "Delete",
        onConfirm = {},
        onDismiss = {},
        style = ConfirmDialogStyle.Destructive,
    )
  }
}
