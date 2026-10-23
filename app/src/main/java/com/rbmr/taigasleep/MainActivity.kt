package com.rbmr.taigasleep

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rbmr.taigasleep.ui.TaigaApp
import com.rbmr.taigasleep.ui.theme.TaigaTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      TaigaTheme {
        TaigaApp()
      }
    }
  }
}
