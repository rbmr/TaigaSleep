package com.rbmr.taigasleep.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.rbmr.taigasleep.GuidanceStore
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.ThemeMode
import com.rbmr.taigasleep.guidance.FlowScreen
import com.rbmr.taigasleep.guidance.GuidanceFlows
import com.rbmr.taigasleep.remote.CheckResult
import com.rbmr.taigasleep.remote.RemoteError
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.ui.components.BottomBarItem
import com.rbmr.taigasleep.ui.components.TaigaBottomBar
import com.rbmr.taigasleep.ui.screens.AboutScreen
import com.rbmr.taigasleep.ui.screens.HistoryScreen
import com.rbmr.taigasleep.ui.screens.HomeScreen
import com.rbmr.taigasleep.ui.screens.NightDetailScreen
import com.rbmr.taigasleep.ui.screens.RemoteScreen
import com.rbmr.taigasleep.ui.screens.SettingsScreen
import com.rbmr.taigasleep.upload.UploadScheduler
import kotlinx.coroutines.launch

private data class Tab(
    val labelRes: Int,
    val icon: ImageVector,
)

private val tabs =
    listOf(
        Tab(R.string.nav_history, Icons.Outlined.History),
        Tab(R.string.nav_home, Icons.Outlined.Home),
        Tab(R.string.nav_settings, Icons.Outlined.Tune),
    )

@Composable
fun TaigaApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
  val pagerState = rememberPagerState(initialPage = 1, pageCount = { tabs.size })
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val guidanceStore = remember { GuidanceStore(context) }
  val remote = remember { RemoteRepository.of(context) }
  var showAbout by rememberSaveable { mutableStateOf(false) }
  var showGuidance by rememberSaveable { mutableStateOf(false) }
  var showRemote by rememberSaveable { mutableStateOf(false) }
  var detailLocal by rememberSaveable { mutableStateOf<String?>(null) }
  var detailRemote by rememberSaveable { mutableStateOf<String?>(null) }
  var detailAnalysis by rememberSaveable { mutableStateOf<String?>(null) }
  // Prompt once per process; a successful check arms it again for a later expiry.
  var promptedLogin by remember { mutableStateOf(false) }
  val loginPrompt = stringResource(R.string.remote_login_prompt)
  val loginAction = stringResource(R.string.remote_login_prompt_action)

  // The same check as the Test button, without a password. Recording never waits for it.
  LifecycleResumeEffect(Unit) {
    scope.launch {
      when (val result = remote.verify()) {
        is CheckResult.Success -> {
          promptedLogin = false
          UploadScheduler.resumePendingDeletes(context)
          UploadScheduler.reconcile(context)
        }
        is CheckResult.Failure -> {
          val needsLogin =
              result.error == RemoteError.SessionExpired ||
                  result.error == RemoteError.BadCredentials
          if (needsLogin && !promptedLogin) {
            promptedLogin = true
            // The Remote screen already shows the state, so the snackbar is only for the tabs.
            if (!showRemote) {
              val answer =
                  snackbarHostState.showSnackbar(
                      message = loginPrompt,
                      actionLabel = loginAction,
                      withDismissAction = true,
                      duration = SnackbarDuration.Long,
                  )
              if (answer == SnackbarResult.ActionPerformed) showRemote = true
            }
          }
        }
        null -> Unit
      }
    }
    onPauseOrDispose {}
  }

  when {
    showRemote -> RemoteScreen(onBack = { showRemote = false })
    detailLocal != null || detailRemote != null ->
        NightDetailScreen(
            localId = detailLocal,
            remoteId = detailRemote,
            analysisId = detailAnalysis,
            onBack = {
              detailLocal = null
              detailRemote = null
              detailAnalysis = null
            },
            onLogin = { showRemote = true },
        )
    showGuidance -> {
      val preview = GuidanceFlows.previewOf(guidanceStore)
      val match = remember(preview) { GuidanceFlows.currentMatch(preview) }
      FlowScreen(
          match = match,
          store = guidanceStore,
          preview = preview != null,
          onClose = { showGuidance = false },
      )
    }
    showAbout -> AboutScreen(onBack = { showAbout = false })
    else ->
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
              TaigaBottomBar(
                  items = tabs.map { BottomBarItem(stringResource(it.labelRes), it.icon) },
                  selectedIndex = pagerState.currentPage,
                  onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
              )
            },
        ) { padding ->
          HorizontalPager(
              state = pagerState,
              modifier = Modifier.padding(padding).fillMaxSize(),
          ) { page ->
            when (page) {
              0 ->
                  HistoryScreen(
                      onOpenNight = { local, server, analysis ->
                        detailLocal = local
                        detailRemote = server
                        detailAnalysis = analysis
                      },
                      onLoginClick = { showRemote = true },
                  )
              1 -> HomeScreen()
              2 ->
                  SettingsScreen(
                      themeMode = themeMode,
                      onThemeModeChange = onThemeModeChange,
                      onAboutClick = { showAbout = true },
                      onGuidanceClick = { showGuidance = true },
                      onRemoteClick = { showRemote = true },
                  )
            }
          }
        }
  }
}
