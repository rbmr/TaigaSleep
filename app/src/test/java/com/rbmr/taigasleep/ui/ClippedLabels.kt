package com.rbmr.taigasleep.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult

/**
 * Every label drawn in the tree that was given less room than its text needs. A label that wraps
 * onto another line is not cut off; a label that is ellipsized, or that a fixed height clips, is,
 * and either way the text reports that it overflows its height. Charts draw their labels on a
 * canvas, so those are not in the tree and are covered by the room they reserve instead.
 */
internal fun ComposeTestRule.clippedLabels(): List<String> =
    labels().mapNotNull { drawn ->
      if (!drawn.layout.didOverflowHeight) return@mapNotNull null
      "${drawn.text} (${drawn.layout.lineCount} lines in ${drawn.height} px)"
    }

/** Every label drawn in the tree, whether or not it fits. */
internal fun ComposeTestRule.allLabels(): List<String> = labels().map { it.text }

/** One label as it was drawn: its text, the layout it was given and the height it had for it. */
private class DrawnLabel(val text: String, val layout: TextLayoutResult, val height: Int)

private fun ComposeTestRule.labels(): List<DrawnLabel> =
    onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
        .mapNotNull { node ->
          val action = node.config[SemanticsActions.GetTextLayoutResult].action
          val layouts = mutableListOf<TextLayoutResult>()
          action?.invoke(layouts)
          val layout = layouts.firstOrNull() ?: return@mapNotNull null
          val text =
              node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
          DrawnLabel(text, layout, node.size.height)
        }
