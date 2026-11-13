package com.rbmr.taigasleep.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ui.theme.taiga

/** One explanation: what a number means, where it came from and the optional source. */
data class InfoContent(
    val title: String,
    val body: String,
    val source: String? = null,
)

/** The one place explanations go: a sheet with a title, its body and an optional source. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(info: InfoContent, onDismiss: () -> Unit) {
  ModalBottomSheet(onDismissRequest = onDismiss) { InfoSheetContent(info) }
}

@Composable
internal fun InfoSheetContent(info: InfoContent, modifier: Modifier = Modifier) {
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .navigationBarsPadding()
              .padding(horizontal = MaterialTheme.taiga.space.m)
              .padding(bottom = MaterialTheme.taiga.space.l),
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.taiga.space.s),
  ) {
    Text(text = info.title, style = MaterialTheme.taiga.text.section)
    Text(text = info.body, style = MaterialTheme.taiga.text.body)
    info.source?.let { source ->
      Text(
          text = stringResource(R.string.night_metric_source, source),
          style = MaterialTheme.taiga.text.meta,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun InfoSheetPreview() {
  PreviewTheme {
    InfoSheetContent(
        InfoContent(
            title = "Sleep score",
            body =
                "The score combines sleep efficiency, time awake and the deep and REM share of " +
                    "sleep.",
            source = "Analysis result",
        )
    )
  }
}
