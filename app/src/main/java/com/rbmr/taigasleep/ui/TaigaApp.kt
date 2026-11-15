package com.rbmr.taigasleep.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.RemoteError
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.guard.GuidanceFlows
import com.rbmr.taigasleep.guard.GuidanceStore
import com.rbmr.taigasleep.ui.components.BottomBarItem
import com.rbmr.taigasleep.ui.components.TaigaBottomBar
import com.rbmr.taigasleep.ui.home.HomeScreen
import com.rbmr.taigasleep.ui.recording.RecordingDetailScreen
import com.rbmr.taigasleep.ui.history.HistoryScreen
import com.rbmr.taigasleep.ui.settings.AboutScreen
import com.rbmr.taigasleep.ui.settings.AccessTokensScreen
import com.rbmr.taigasleep.ui.settings.AnalysisScreen
import com.rbmr.taigasleep.ui.settings.FlowScreen
import com.rbmr.taigasleep.ui.settings.LicencesScreen
import com.rbmr.taigasleep.ui.settings.PersonalDataScreen
import com.rbmr.taigasleep.ui.settings.RemoteScreen
import com.rbmr.taigasleep.ui.settings.SettingsScreen
import com.rbmr.taigasleep.ui.settings.UploadsScreen
import com.rbmr.taigasleep.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun TaigaApp(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
  val pagerState =
      rememberPagerState(initialPage = MainTab.HOME.ordinal, pageCount = { MainTab.entries.size })
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val context = LocalContext.current
  val guidanceStore = remember { GuidanceStore(context) }
  val remote = remember { context.appContainer.remoteRepository }
  val remoteStatus by remote.status.collectAsStateWithLifecycle()
  var showAbout by rememberSaveable { mutableStateOf(false) }
  var showLicences by rememberSaveable { mutableStateOf(false) }
  var showGuidance by rememberSaveable { mutableStateOf(false) }
  var showRemote by rememberSaveable { mutableStateOf(false) }
  var showTokens by rememberSaveable { mutableStateOf(false) }
  var showPersonal by rememberSaveable { mutableStateOf(false) }
  var showUploads by rememberSaveable { mutableStateOf(false) }
  var showAnalysis by rememberSaveable { mutableStateOf(false) }
  var detailLocal by rememberSaveable { mutableStateOf<String?>(null) }
  var detailRemote by rememberSaveable { mutableStateOf<String?>(null) }
  var detailAnalysis by rememberSaveable { mutableStateOf<String?>(null) }
  val tab = MainTab.entries[pagerState.currentPage]
  val top =
      mainOverlay(
          remote = showRemote,
          tokens = showTokens,
          personal = showPersonal,
          uploads = showUploads,
          analysis = showAnalysis,
          night = detailLocal != null || detailRemote != null,
          guidance = showGuidance,
          licences = showLicences,
          about = showAbout,
      )
  // Prompt once per process; a successful check arms it again for a later expiry.
  var promptedLogin by remember { mutableStateOf(false) }
  val loginPrompt = stringResource(R.string.server_login_prompt)
  val loginAction = stringResource(R.string.server_login_prompt_action)

  // The same check as the Remote screen, without a password. Recording never waits for it.
  LifecycleResumeEffect(Unit) {
    scope.launch {
      remote.verify()
      if (remote.status.value is RemoteStatus.Connected) {
        context.appContainer.syncService.reconcileAll()
      }
    }
    onPauseOrDispose {}
  }

  // The prompt follows the shared state, wherever the rejected token was noticed.
  LaunchedEffect(remoteStatus) {
    when (val status = remoteStatus) {
      is RemoteStatus.Connected -> promptedLogin = false
      is RemoteStatus.LoginNeeded -> {
        val rejectedToken =
            status.reason == RemoteError.SessionExpired ||
                status.reason == RemoteError.BadCredentials
        if (rejectedToken && !promptedLogin && !showRemote) {
          promptedLogin = true
          // The Remote screen already shows the state, so the snackbar is only for the tabs.
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
      else -> Unit
    }
  }

  // Back selects Home from any tab other than Home, and leaves the app on Home as it always has.
  // What is open above the pager answers Back itself, so it is closed first.
  BackHandler(
      enabled = backReturnsHome(top, tab),
      onBack = { scope.launch { pagerState.animateScrollToPage(MainTab.HOME.ordinal) } },
  )

  when (top) {
    MainOverlay.REMOTE -> RemoteScreen(onBack = { showRemote = false })
    MainOverlay.TOKENS -> AccessTokensScreen(onBack = { showTokens = false })
    MainOverlay.PERSONAL -> PersonalDataScreen(onBack = { showPersonal = false })
    MainOverlay.UPLOADS -> UploadsScreen(onBack = { showUploads = false })
    MainOverlay.ANALYSIS -> AnalysisScreen(onBack = { showAnalysis = false })
    MainOverlay.NIGHT ->
        RecordingDetailScreen(
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
    MainOverlay.GUIDANCE -> {
      val preview = GuidanceFlows.previewOf(guidanceStore)
      val match = remember(preview) { GuidanceFlows.currentMatch(preview) }
      FlowScreen(
          match = match,
          store = guidanceStore,
          preview = preview != null,
          onClose = { showGuidance = false },
      )
    }
    MainOverlay.LICENCES -> LicencesScreen(onBack = { showLicences = false })
    MainOverlay.ABOUT -> AboutScreen(onBack = { showAbout = false }, onLicences = { showLicences = true })
    MainOverlay.NONE ->
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
              TaigaBottomBar(
                  items =
                      MainTab.entries.map {
                        BottomBarItem(stringResource(it.labelRes), it.icon)
                      },
                  selectedIndex = pagerState.currentPage,
                  onSelect = { scope.launch { pagerState.animateScrollToPage(it) } },
              )
            },
        ) { padding ->
          HorizontalPager(
              state = pagerState,
              // The bottom bar already sits above the navigation bar, so a tab never adds that inset again.
              modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize(),
          ) { page ->
            when (MainTab.entries[page]) {
              MainTab.HISTORY ->
                  HistoryScreen(
                      onOpenRecording = { local, server, analysis ->
                        detailLocal = local
                        detailRemote = server
                        detailAnalysis = analysis
                      },
                      onOpenServer = { showRemote = true },
                  )
              MainTab.HOME ->
                  HomeScreen(
                      onGuidanceClick = { showGuidance = true },
                      onOpenRecording = { recordingId ->
                        detailLocal = recordingId
                        detailRemote = null
                        detailAnalysis = null
                      },
                  )
              MainTab.SETTINGS ->
                  SettingsScreen(
                      themeMode = themeMode,
                      onThemeModeChange = onThemeModeChange,
                      onAboutClick = { showAbout = true },
                      onGuidanceClick = { showGuidance = true },
                      onRemoteClick = { showRemote = true },
                      onTokensClick = { showTokens = true },
                      onPersonalClick = { showPersonal = true },
                      onUploadsClick = { showUploads = true },
                      onAnalysisClick = { showAnalysis = true },
                  )
            }
          }
        }
  }
}
