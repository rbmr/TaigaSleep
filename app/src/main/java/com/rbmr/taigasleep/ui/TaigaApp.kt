package com.rbmr.taigasleep.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
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
import com.rbmr.taigasleep.ui.components.BottomBarItem
import com.rbmr.taigasleep.ui.components.TaigaBottomBar
import com.rbmr.taigasleep.ui.history.HistoryScreen
import com.rbmr.taigasleep.ui.home.HomeScreen
import com.rbmr.taigasleep.ui.recording.RecordingDetailScreen
import com.rbmr.taigasleep.ui.settings.AboutScreen
import com.rbmr.taigasleep.ui.settings.AccessTokensScreen
import com.rbmr.taigasleep.ui.settings.AnalysisScreen
import com.rbmr.taigasleep.ui.settings.DisplayScreen
import com.rbmr.taigasleep.ui.settings.FlowScreen
import com.rbmr.taigasleep.ui.settings.LicencesScreen
import com.rbmr.taigasleep.ui.settings.NotificationsScreen
import com.rbmr.taigasleep.ui.settings.PersonalDataScreen
import com.rbmr.taigasleep.ui.settings.RemoteScreen
import com.rbmr.taigasleep.ui.settings.SettingsScreen
import com.rbmr.taigasleep.ui.settings.UploadsScreen
import com.rbmr.taigasleep.ui.theme.LocalMotionScale
import com.rbmr.taigasleep.ui.theme.animated
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The app after the theme: the tabs, and whatever the user opened above them. The tabs keep their
 * page, and a page above them closes back to the one it was opened from.
 */
@Composable
fun TaigaApp() {
  val context = LocalContext.current
  val nav = rememberSaveable(saver = MainNavigation.Saver) { MainNavigation() }
  val pagerState =
      rememberPagerState(initialPage = MainTab.HOME.ordinal, pageCount = { MainTab.entries.size })
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  val motionScale = LocalMotionScale.current
  val remote = remember { context.appContainer.remoteRepository }
  val remoteStatus by remote.status.collectAsStateWithLifecycle()
  val top = nav.top
  val tab = MainTab.entries[pagerState.currentPage]

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

  LoginPrompt(
      status = remoteStatus,
      remoteOpen = top == MainOverlay.REMOTE,
      snackbarHostState = snackbarHostState,
      onOpenRemote = { nav.show(MainOverlay.REMOTE) },
  )

  // Back selects Home from any tab other than Home, and leaves the app on Home as it always has.
  // What is open above the pager answers Back itself, so it is closed first.
  BackHandler(enabled = backReturnsHome(top, tab)) {
    pagerState.moveTo(MainTab.HOME.ordinal, motionScale, scope)
  }

  if (top == MainOverlay.NONE) {
    Tabs(
        pagerState = pagerState,
        snackbarHostState = snackbarHostState,
        nav = nav,
        onSelect = { page -> pagerState.moveTo(page, motionScale, scope) },
    )
  } else {
    Overlays(top = top, nav = nav)
  }
}

/** The three tabs and the bottom bar, drawn over the state each screen holds. */
@Composable
private fun Tabs(
    pagerState: PagerState,
    snackbarHostState: SnackbarHostState,
    nav: MainNavigation,
    onSelect: (Int) -> Unit,
) {
  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      snackbarHost = { SnackbarHost(snackbarHostState) },
      bottomBar = {
        TaigaBottomBar(
            items = MainTab.entries.map { BottomBarItem(stringResource(it.labelRes), it.icon) },
            selectedIndex = pagerState.currentPage,
            onSelect = onSelect,
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
                  nav.openRecording(local, server, analysis)
                },
                onOpenServer = { nav.show(MainOverlay.REMOTE) },
            )
        MainTab.HOME ->
            HomeScreen(
                onGuidanceClick = { nav.show(MainOverlay.GUIDANCE) },
                onOpenRecording = { recordingId ->
                  nav.openRecording(local = recordingId, remote = null, analysis = null)
                },
            )
        MainTab.SETTINGS -> SettingsScreen(onOpen = nav::show)
      }
    }
  }
}

/** The one page drawn above the tabs, from what the user opened. */
@Composable
private fun Overlays(top: MainOverlay, nav: MainNavigation) {
  when (top) {
    MainOverlay.REMOTE -> RemoteScreen(onBack = nav::hide)
    MainOverlay.TOKENS -> AccessTokensScreen(onBack = nav::hide)
    MainOverlay.PERSONAL -> PersonalDataScreen(onBack = nav::hide)
    MainOverlay.UPLOADS -> UploadsScreen(onBack = nav::hide)
    MainOverlay.ANALYSIS -> AnalysisScreen(onBack = nav::hide)
    MainOverlay.NOTIFICATIONS -> NotificationsScreen(onBack = nav::hide)
    MainOverlay.DISPLAY -> DisplayScreen(onBack = nav::hide)
    MainOverlay.NIGHT ->
        RecordingDetailScreen(
            localId = nav.detailLocal,
            remoteId = nav.detailRemote,
            analysisId = nav.detailAnalysis,
            onBack = nav::hide,
            onLogin = { nav.show(MainOverlay.REMOTE) },
        )
    MainOverlay.GUIDANCE -> GuidanceOverlay(onClose = nav::hide)
    MainOverlay.LICENCES -> LicencesScreen(onBack = nav::hide)
    MainOverlay.ABOUT ->
        AboutScreen(onBack = nav::hide, onLicences = { nav.show(MainOverlay.LICENCES) })
    MainOverlay.NONE -> Unit
  }
}

/** The preventing-shutdown flow, drawn from the answers the guidance store holds. */
@Composable
private fun GuidanceOverlay(onClose: () -> Unit) {
  val store = LocalContext.current.appContainer.guidanceStore
  val preview = GuidanceFlows.previewOf(store)
  val match = remember(preview) { GuidanceFlows.currentMatch(preview) }
  FlowScreen(match = match, store = store, preview = preview != null, onClose = onClose)
}

/**
 * The prompt that offers the Remote screen when the token was rejected, wherever that was noticed.
 * It asks once per process; a successful check arms it again for a later expiry.
 */
@Composable
private fun LoginPrompt(
    status: RemoteStatus,
    remoteOpen: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenRemote: () -> Unit,
) {
  var promptedLogin by remember { mutableStateOf(false) }
  val loginPrompt = stringResource(R.string.server_login_prompt)
  val loginAction = stringResource(R.string.server_login_prompt_action)

  LaunchedEffect(status) {
    when (status) {
      is RemoteStatus.Connected -> promptedLogin = false
      is RemoteStatus.LoginNeeded -> {
        val rejectedToken =
            status.reason == RemoteError.SessionExpired ||
                status.reason == RemoteError.BadCredentials
        if (rejectedToken && !promptedLogin && !remoteOpen) {
          promptedLogin = true
          // The Remote screen already shows the state, so the snackbar is only for the tabs.
          val answer =
              snackbarHostState.showSnackbar(
                  message = loginPrompt,
                  actionLabel = loginAction,
                  withDismissAction = true,
                  duration = SnackbarDuration.Long,
              )
          if (answer == SnackbarResult.ActionPerformed) onOpenRemote()
        }
      }
      else -> Unit
    }
  }
}

/** Moves the pager to [page]: a slide at the app's animation scale, or a jump when it is off. */
private fun PagerState.moveTo(page: Int, motionScale: Float, scope: CoroutineScope) {
  scope.launch {
    if (motionScale <= 0f) scrollToPage(page)
    else animated(motionScale) { animateScrollToPage(page) }
  }
}
