package com.rbmr.taigasleep.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.backend.AddressCheck
import com.rbmr.taigasleep.backend.CheckResult
import com.rbmr.taigasleep.backend.RemoteError
import com.rbmr.taigasleep.backend.RemoteRepository
import com.rbmr.taigasleep.backend.RemoteState
import com.rbmr.taigasleep.backend.RemoteStatus
import com.rbmr.taigasleep.backend.RemoteUrl
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.ScreenHeader
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.SegmentedProgress
import com.rbmr.taigasleep.ui.components.StatusChip
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The three pages of the flow. Which one is shown is the user's choice, not the state's. */
private enum class RemotePage(val titleRes: Int) {
  SERVER(R.string.remote_page_server),
  ACCOUNT(R.string.remote_page_account),
  STATUS(R.string.remote_page_status),
}

/**
 * Settings → Remote, as three self-contained pages: the server, the account and the status.
 *
 * Each page shows only its own field and its own action, and nothing about the other pages.
 * Swiping sideways or the Previous/Next buttons switch pages even when a page cannot act yet; in
 * that case its action is simply disabled. The connection state lives in [RemoteRepository], so no
 * page keeps a copy of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val repository = remember { RemoteRepository.of(context) }
  val status by repository.status.collectAsStateWithLifecycle()
  val state by repository.state.collectAsStateWithLifecycle()
  val address by repository.address.collectAsStateWithLifecycle()
  val checking by repository.checking.collectAsStateWithLifecycle()

  var url by rememberSaveable { mutableStateOf(repository.savedUrl) }
  var email by rememberSaveable { mutableStateOf(repository.savedEmail) }
  var password by rememberSaveable { mutableStateOf("") }
  var passwordVisible by rememberSaveable { mutableStateOf(false) }
  var showLogout by rememberSaveable { mutableStateOf(false) }
  // Opens where the saved state is most useful, then the user pages through by swipe or by button.
  val pagerState =
      rememberPagerState(initialPage = initialPage(repository.state.value).ordinal) {
        RemotePage.entries.size
      }
  val current = RemotePage.entries[pagerState.currentPage]
  val scope = rememberCoroutineScope()

  // The saved session is re-checked on every resume; the server and account pages are untouched.
  LifecycleResumeEffect(Unit) {
    scope.launch { repository.verify() }
    onPauseOrDispose {}
  }

  fun goTo(target: RemotePage) {
    scope.launch { pagerState.animateScrollToPage(target.ordinal) }
  }

  // Server page: the address is the only thing it sets, and a reachable one hands the user on.
  fun checkServer() {
    scope.launch {
      val checked = repository.checkAddress(url)
      if (checked is AddressCheck.Reachable) {
        val connected = repository.state.value == RemoteState.CONNECTED
        goTo(if (connected) RemotePage.STATUS else RemotePage.ACCOUNT)
      }
    }
  }

  // Account page: only a stored session can move the user on.
  fun logIn() {
    val target = address ?: return
    scope.launch {
      if (repository.login(target, email, password) is CheckResult.Success) {
        password = ""
        goTo(RemotePage.STATUS)
      }
    }
  }

  // Back leaves the screen; the page arrows at the bottom do the paging.
  BackHandler(onBack = onBack)

  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
        Column {
          TopAppBar(
              title = { Text(stringResource(R.string.remote_title)) },
              colors =
                  TopAppBarDefaults.topAppBarColors(
                      containerColor = MaterialTheme.colorScheme.background
                  ),
              navigationIcon = {
                IconButton(onClick = onBack) {
                  Icon(
                      imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                      contentDescription = stringResource(R.string.remote_back),
                  )
                }
              },
          )
          SegmentedProgress(
              steps = RemotePage.entries.size,
              current = pagerState.currentPage + 1,
              description =
                  stringResource(
                      R.string.remote_progress,
                      pagerState.currentPage + 1,
                      RemotePage.entries.size,
                      stringResource(current.titleRes),
                  ),
              modifier = Modifier.padding(horizontal = 20.dp),
          )
        }
      },
      bottomBar = {
        Surface(color = MaterialTheme.colorScheme.background) {
          Row(
              modifier =
                  Modifier.fillMaxWidth()
                      .navigationBarsPadding()
                      .imePadding()
                      .padding(horizontal = 20.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            SecondaryButton(
                text = stringResource(R.string.remote_previous_page),
                onClick = { goTo(RemotePage.entries[pagerState.currentPage - 1]) },
                modifier = Modifier.weight(1f),
                enabled = current != RemotePage.SERVER,
            )
            SecondaryButton(
                text = stringResource(R.string.remote_next_page),
                onClick = { goTo(RemotePage.entries[pagerState.currentPage + 1]) },
                modifier = Modifier.weight(1f),
                enabled = current != RemotePage.STATUS,
            )
          }
        }
      },
  ) { padding ->
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().padding(padding),
    ) { index ->
      val shown = RemotePage.entries[index]
      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 20.dp, vertical = 24.dp),
          verticalArrangement = Arrangement.spacedBy(18.dp),
      ) {
        when (shown) {
          RemotePage.SERVER ->
              ServerPage(
                  url = url,
                  onUrlChange = { url = it },
                  address = address,
                  status = status,
                  checking = checking,
                  onCheck = { checkServer() },
              )
          RemotePage.ACCOUNT ->
              AccountPage(
                  hasRemote = address != null,
                  email = email,
                  onEmailChange = { email = it },
                  password = password,
                  onPasswordChange = { password = it },
                  passwordVisible = passwordVisible,
                  onTogglePassword = { passwordVisible = !passwordVisible },
                  failure = (status as? RemoteStatus.LoginNeeded)?.reason,
                  checking = checking,
                  onLogin = { logIn() },
              )
          RemotePage.STATUS ->
              StatusPage(
                  state = state,
                  status = status,
                  checking = checking,
                  hasRemote = address != null,
                  hasSession = repository.hasSession,
                  onCheck = { scope.launch { repository.checkStatus() } },
                  onLogout = { showLogout = true },
              )
        }
      }
    }
  }

  if (showLogout) {
    AlertDialog(
        onDismissRequest = { showLogout = false },
        title = { Text(stringResource(R.string.remote_logout_title)) },
        text = { Text(stringResource(R.string.remote_logout_body)) },
        confirmButton = {
          TextButton(
              onClick = {
                showLogout = false
                password = ""
                repository.logout()
              }
          ) {
            Text(
                text = stringResource(R.string.remote_logout_confirm),
                color = MaterialTheme.taiga.bad,
            )
          }
        },
        dismissButton = {
          TextButton(onClick = { showLogout = false }) {
            Text(stringResource(R.string.remote_cancel))
          }
        },
    )
  }
}

/** Server: the address, the check that proves it answers, and nothing else. */
@Composable
private fun ServerPage(
    url: String,
    onUrlChange: (String) -> Unit,
    address: String?,
    status: RemoteStatus,
    checking: Boolean,
    onCheck: () -> Unit,
) {
  // A check result only describes the address it ran against, so a typed address that has not been
  // checked yet shows neither the old error nor the old success.
  val normalized = remember(url) { RemoteUrl.parse(url).getOrNull()?.baseUrl }
  val checked = address != null && normalized == address
  val failure = (status as? RemoteStatus.Unreachable)?.reason?.takeIf { checked }
  val reachable = checked && status !is RemoteStatus.Unreachable

  ScreenHeader(
      kicker = stringResource(R.string.remote_kicker),
      title = stringResource(R.string.remote_page_server),
      subtitle = stringResource(R.string.remote_page_server_body),
  )
  TaigaCard {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.remote_url_label)) },
        placeholder = { Text(stringResource(R.string.remote_url_placeholder)) },
        singleLine = true,
        isError = failure != null,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        trailingIcon = {
          when {
            checking ->
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            reachable ->
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.remote_address_ok),
                    tint = MaterialTheme.taiga.ok,
                )
            failure != null ->
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(R.string.remote_address_failed),
                    tint = MaterialTheme.taiga.bad,
                )
          }
        },
    )
  }
  if (failure != null) {
    Text(
        text = errorMessage(failure),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.taiga.bad,
    )
  }
  PrimaryButton(
      text =
          stringResource(
              when {
                checking -> R.string.remote_checking
                failure != null -> R.string.remote_retry
                else -> R.string.remote_check_server
              }
          ),
      onClick = onCheck,
      enabled = !checking && url.isNotBlank(),
      size = ButtonSize.Large,
  )
}

/** Account: the email, the password, the login that stores a token, and nothing else. */
@Composable
private fun AccountPage(
    hasRemote: Boolean,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    failure: RemoteError?,
    checking: Boolean,
    onLogin: () -> Unit,
) {
  ScreenHeader(
      kicker = stringResource(R.string.remote_kicker),
      title = stringResource(R.string.remote_page_account),
      subtitle = stringResource(R.string.remote_page_account_body),
  )
  TaigaCard {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(
          value = email,
          onValueChange = onEmailChange,
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.remote_email_label)) },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
      )
      OutlinedTextField(
          value = password,
          onValueChange = onPasswordChange,
          modifier = Modifier.fillMaxWidth(),
          label = { Text(stringResource(R.string.remote_password_label)) },
          singleLine = true,
          shape = RoundedCornerShape(16.dp),
          visualTransformation =
              if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          trailingIcon = {
            IconButton(onClick = onTogglePassword) {
              Icon(
                  imageVector =
                      if (passwordVisible) Icons.Outlined.VisibilityOff
                      else Icons.Outlined.Visibility,
                  contentDescription =
                      stringResource(
                          if (passwordVisible) R.string.remote_hide_password
                          else R.string.remote_show_password
                      ),
              )
            }
          },
      )
    }
  }
  if (failure != null) {
    Text(
        text = errorMessage(failure),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.taiga.bad,
    )
  }
  PrimaryButton(
      text = stringResource(if (checking) R.string.remote_checking else R.string.remote_login),
      onClick = onLogin,
      enabled = !checking && hasRemote && email.isNotBlank() && password.isNotEmpty(),
      size = ButtonSize.Large,
  )
}

/** Status: what the connection is right now, the check that re-decides it, and logging out. */
@Composable
private fun StatusPage(
    state: RemoteState,
    status: RemoteStatus,
    checking: Boolean,
    hasRemote: Boolean,
    hasSession: Boolean,
    onCheck: () -> Unit,
    onLogout: () -> Unit,
) {
  ScreenHeader(
      kicker = stringResource(R.string.remote_kicker),
      title = stringResource(R.string.remote_page_status),
      subtitle = stringResource(R.string.remote_page_status_body),
  )
  TaigaCard {
    StatusChip(kind = state.kind, label = stringResource(state.labelRes))
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(state.explanationRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.extra(status)?.let { extra ->
      Spacer(Modifier.height(4.dp))
      Text(
          text = extra,
          style = MaterialTheme.typography.bodyMedium,
          color =
              if (state == RemoteState.UNREACHABLE) MaterialTheme.taiga.bad
              else MaterialTheme.colorScheme.onSurface,
      )
    }
    val checkedAt = status.checkedAt()
    if (checkedAt != null) {
      Spacer(Modifier.height(6.dp))
      LastCheck(checkedAt)
    }
  }
  PrimaryButton(
      text =
          stringResource(
              if (checking) R.string.remote_checking else R.string.remote_check_connection
          ),
      onClick = onCheck,
      enabled = !checking && hasRemote,
      size = ButtonSize.Large,
  )
  SecondaryButton(
      text = stringResource(R.string.remote_logout),
      onClick = onLogout,
      modifier = Modifier.fillMaxWidth(),
      enabled = !checking && hasSession,
  )
}

/** The last check, kept honest while the page is open. */
@Composable
private fun LastCheck(checkedAtMs: Long) {
  var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(checkedAtMs) {
    nowMs = System.currentTimeMillis()
    while (true) {
      delay(1_000)
      nowMs = System.currentTimeMillis()
    }
  }
  val relative =
      DateUtils.getRelativeTimeSpanString(
              checkedAtMs,
              nowMs,
              DateUtils.SECOND_IN_MILLIS,
              DateUtils.FORMAT_ABBREV_RELATIVE,
          )
          .toString()
  Text(
      text = stringResource(R.string.remote_last_check, relative),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

private fun initialPage(state: RemoteState): RemotePage =
    when (state) {
      RemoteState.CONNECTED -> RemotePage.STATUS
      RemoteState.NOT_LOGGED_IN -> RemotePage.ACCOUNT
      RemoteState.NO_REMOTE, RemoteState.UNREACHABLE -> RemotePage.SERVER
    }

private val RemoteState.labelRes: Int
  get() =
      when (this) {
        RemoteState.NO_REMOTE -> R.string.remote_status_none
        RemoteState.UNREACHABLE -> R.string.remote_status_unreachable
        RemoteState.NOT_LOGGED_IN -> R.string.remote_status_not_logged_in
        RemoteState.CONNECTED -> R.string.remote_status_connected
      }

private val RemoteState.kind: StatusKind
  get() =
      when (this) {
        RemoteState.NO_REMOTE -> StatusKind.Warn
        RemoteState.UNREACHABLE -> StatusKind.Bad
        RemoteState.NOT_LOGGED_IN -> StatusKind.Warn
        RemoteState.CONNECTED -> StatusKind.Ok
      }

/** The fixed explanation of each status; it never changes with the details of a check. */
private val RemoteState.explanationRes: Int
  get() =
      when (this) {
        RemoteState.NO_REMOTE -> R.string.remote_status_none_body
        RemoteState.UNREACHABLE -> R.string.remote_status_unreachable_body
        RemoteState.NOT_LOGGED_IN -> R.string.remote_status_not_logged_in_body
        RemoteState.CONNECTED -> R.string.remote_status_connected_body
      }

/** What the status can add to its explanation: the account, or why the check failed. */
@Composable
private fun RemoteState.extra(status: RemoteStatus): String? =
    when (this) {
      RemoteState.UNREACHABLE ->
          (status as? RemoteStatus.Unreachable)?.reason?.let { errorMessage(it) }
      RemoteState.CONNECTED ->
          (status as? RemoteStatus.Connected)?.email?.takeIf { it.isNotBlank() }
      RemoteState.NO_REMOTE, RemoteState.NOT_LOGGED_IN -> null
    }

private fun RemoteStatus.checkedAt(): Long? =
    when (this) {
      RemoteStatus.NotConfigured -> null
      is RemoteStatus.Connected -> checkedAtMs
      is RemoteStatus.LoginNeeded -> checkedAtMs
      is RemoteStatus.Unreachable -> checkedAtMs
    }

@Composable
private fun errorMessage(error: RemoteError): String =
    when (error) {
      RemoteError.InvalidUrl -> stringResource(R.string.remote_error_invalid_url)
      RemoteError.UnknownHost -> stringResource(R.string.remote_error_unknown_host)
      RemoteError.InsecureUrl -> stringResource(R.string.remote_error_insecure_url)
      RemoteError.MissingCredentials -> stringResource(R.string.remote_error_missing_credentials)
      RemoteError.Unreachable -> stringResource(R.string.remote_error_unreachable)
      RemoteError.NotTaiga -> stringResource(R.string.remote_error_not_taiga)
      RemoteError.BadCredentials -> stringResource(R.string.remote_error_bad_credentials)
      RemoteError.SessionExpired -> stringResource(R.string.remote_error_session_expired)
      is RemoteError.IncompatibleApi ->
          when {
            // A server from before API versioning answers with nothing, which is too old by
            // definition.
            error.server == 0 ->
                stringResource(R.string.remote_error_api_unversioned, error.required)
            error.server < error.required ->
                stringResource(R.string.remote_error_api_server_old, error.required, error.server)
            else -> stringResource(R.string.remote_error_api_app_old, error.required, error.server)
          }
      is RemoteError.Http -> stringResource(R.string.remote_error_http, error.status)
      RemoteError.MalformedResponse -> stringResource(R.string.remote_error_malformed)
    }
