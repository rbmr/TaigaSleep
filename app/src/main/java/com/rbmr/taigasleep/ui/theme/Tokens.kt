package com.rbmr.taigasleep.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The six text roles every screen draws with. Sizes are the design's: hero 40 / 700, screen title
 * 28 / 700, section 20 / 600, row 16 / 600, body 16 / 400 and meta 14 / 500. Copy is never
 * smaller than meta; only axis labels and pills may use a smaller label style.
 *
 * Reached through `MaterialTheme.taiga.text`.
 */
object TaigaText {
  val hero = taigaStyle(FontWeight.Bold, 40, 46, -0.8)
  val screenTitle = taigaStyle(FontWeight.Bold, 28, 34, -0.4)
  val section = taigaStyle(FontWeight.SemiBold, 20, 26)
  val row = taigaStyle(FontWeight.SemiBold, 16, 22)
  val body = taigaStyle(FontWeight.Normal, 16, 24)
  val meta = taigaStyle(FontWeight.Medium, 14, 20)
}

/**
 * The spacing scale: 4, 8, 16 and 24 dp. Padding and gaps come from here so one screen cannot
 * drift from the next.
 *
 * Reached through `MaterialTheme.taiga.space`.
 */
object TaigaSpacing {
  val xs = 4.dp
  val s = 8.dp
  val m = 16.dp
  val l = 24.dp
}

/**
 * The two corner radii: controls are 12 dp and cards are 20 dp.
 *
 * Reached through `MaterialTheme.taiga.shape`.
 */
object TaigaShapes {
  val control = RoundedCornerShape(12.dp)
  val card = RoundedCornerShape(20.dp)
}
