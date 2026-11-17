package com.rbmr.taigasleep.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.ui.components.SegmentedControl
import com.rbmr.taigasleep.ui.components.Section
import com.rbmr.taigasleep.ui.components.SettingsDivider
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.components.TaigaScreen
import com.rbmr.taigasleep.ui.theme.AnimationMode
import com.rbmr.taigasleep.ui.theme.TextSize
import com.rbmr.taigasleep.ui.theme.ThemeMode
import com.rbmr.taigasleep.ui.theme.taiga

/**
 * What the Display screen draws: the three choices, resolved, and the action that changes one.
 * One section per choice, so a later display setting is one more section in the same list.
 */
internal data class DisplayUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val textSize: TextSize = TextSize.SYSTEM,
    val animations: AnimationMode = AnimationMode.SYSTEM,
)

/** One display choice the user made. */
internal sealed interface DisplayChange {
  data class Theme(val mode: ThemeMode) : DisplayChange

  data class Text(val size: TextSize) : DisplayChange

  data class Motion(val mode: AnimationMode) : DisplayChange
}

/** Settings → Display: the theme, the text size and the animations, each on its own card. */
@Composable
fun DisplayScreen(onBack: () -> Unit) {
  BackHandler(onBack = onBack)
  val display = LocalContext.current.appContainer.displaySettings
  val themeMode by display.themeMode.collectAsStateWithLifecycle()
  val textSize by display.textSize.collectAsStateWithLifecycle()
  val animations by display.animations.collectAsStateWithLifecycle()
  DisplayContent(
      state = DisplayUiState(themeMode = themeMode, textSize = textSize, animations = animations),
      onChange = { change ->
        when (change) {
          is DisplayChange.Theme -> display.setThemeMode(change.mode)
          is DisplayChange.Text -> display.setTextSize(change.size)
          is DisplayChange.Motion -> display.setAnimations(change.mode)
        }
      },
      onBack = onBack,
  )
}

/** The sections of Display, drawn from [state]. */
@Composable
internal fun DisplayContent(
    state: DisplayUiState,
    onChange: (DisplayChange) -> Unit,
    onBack: () -> Unit,
) {
  TaigaScreen(title = stringResource(R.string.settings_display_title), onBack = onBack) {
    ChoiceSection(
        title = stringResource(R.string.display_theme_title),
        options =
            listOf(
                stringResource(R.string.appearance_system),
                stringResource(R.string.appearance_light),
                stringResource(R.string.appearance_dark),
            ),
        selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
        onSelect = { onChange(DisplayChange.Theme(ThemeMode.entries[it])) },
    )
    TextSizeSection(size = state.textSize, onChange = onChange)
    ChoiceSection(
        title = stringResource(R.string.display_animations_title),
        options =
            listOf(
                stringResource(R.string.appearance_system),
                stringResource(R.string.display_animations_on),
                stringResource(R.string.display_animations_off),
            ),
        selectedIndex = AnimationMode.entries.indexOf(state.animations),
        onSelect = { onChange(DisplayChange.Motion(AnimationMode.entries[it])) },
    )
  }
}

/** The text size, with a line drawn in it: what the choice does to the app's text. */
@Composable
private fun TextSizeSection(
    size: TextSize,
    onChange: (DisplayChange) -> Unit,
) {
  val space = MaterialTheme.taiga.space
  Section(title = stringResource(R.string.display_text_size_title)) {
    TaigaCard(contentPadding = PaddingValues(0.dp)) {
      Column(Modifier.padding(space.m)) {
        SegmentedControl(
            options =
                listOf(
                    stringResource(R.string.appearance_system),
                    stringResource(R.string.display_text_size_small),
                    stringResource(R.string.display_text_size_medium),
                    stringResource(R.string.display_text_size_large),
                ),
            selectedIndex = TextSize.entries.indexOf(size),
            onSelect = { onChange(DisplayChange.Text(TextSize.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
            fillWidth = true,
        )
      }
      SettingsDivider()
      // The sample is drawn in the theme's text size, so it is what the choice does.
      Text(
          text = stringResource(R.string.display_text_sample),
          style = MaterialTheme.taiga.text.body,
          modifier = Modifier.padding(space.m),
      )
    }
  }
}

/** One choice of the screen: the section title and the three-way or two-way control under it. */
@Composable
private fun ChoiceSection(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
  Section(title = title) {
    TaigaCard(contentPadding = PaddingValues(MaterialTheme.taiga.space.m)) {
      SegmentedControl(
          options = options,
          selectedIndex = selectedIndex,
          onSelect = onSelect,
          modifier = Modifier.fillMaxWidth(),
          fillWidth = true,
      )
    }
  }
}
