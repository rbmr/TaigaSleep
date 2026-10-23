package com.rbmr.taigasleep.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ThemeMode
import com.rbmr.taigasleep.ui.screens.HistoryScreen
import com.rbmr.taigasleep.ui.screens.HomeScreen
import com.rbmr.taigasleep.ui.screens.SettingsScreen
import kotlinx.coroutines.launch

val LocalComingSoon = staticCompositionLocalOf<() -> Unit> { {} }

private data class Tab(
    val labelRes: Int,
    val icon: ImageVector,
)

private val tabs =
    listOf(
        Tab(R.string.nav_history, Icons.Outlined.History),
        Tab(R.string.nav_home, Icons.Outlined.Home),
        Tab(R.string.nav_settings, Icons.Outlined.Settings),
    )

@Composable
fun TaigaApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
  val pagerState = rememberPagerState(initialPage = 1, pageCount = { tabs.size })
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val comingSoon: () -> Unit = {
    scope.launch {
      snackbarHostState.currentSnackbarData?.dismiss()
      snackbarHostState.showSnackbar("This feature doesn't exist yet.")
    }
    Unit
  }

  CompositionLocalProvider(LocalComingSoon provides comingSoon) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
          NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            tabs.forEachIndexed { index, tab ->
              NavigationBarItem(
                  selected = pagerState.currentPage == index,
                  onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                  icon = { Icon(tab.icon, contentDescription = stringResource(tab.labelRes)) },
                  label = { Text(stringResource(tab.labelRes)) },
              )
            }
          }
        },
    ) { padding ->
      HorizontalPager(
          state = pagerState,
          modifier = Modifier.padding(padding).fillMaxSize(),
      ) { page ->
        when (page) {
          0 -> HistoryScreen()
          1 -> HomeScreen()
          2 -> SettingsScreen(
              themeMode = themeMode,
              onThemeModeChange = onThemeModeChange,
          )
        }
      }
    }
  }
}
