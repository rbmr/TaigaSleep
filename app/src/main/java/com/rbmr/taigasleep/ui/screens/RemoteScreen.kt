package com.rbmr.taigasleep.ui.screens

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rbmr.taigasleep.R
import com.rbmr.taigasleep.remote.AddressCheck
import com.rbmr.taigasleep.remote.CheckResult
import com.rbmr.taigasleep.remote.RemoteError
import com.rbmr.taigasleep.remote.RemoteRepository
import com.rbmr.taigasleep.remote.RemoteStatus
import com.rbmr.taigasleep.remote.RemoteStep
import com.rbmr.taigasleep.remote.RemoteUrl
import com.rbmr.taigasleep.remote.RemoteUrlException
import com.rbmr.taigasleep.ui.components.ButtonSize
import com.rbmr.taigasleep.ui.components.PrimaryButton
import com.rbmr.taigasleep.ui.components.SecondaryButton
import com.rbmr.taigasleep.ui.components.SectionLabel
import com.rbmr.taigasleep.ui.components.StatusBanner
import com.rbmr.taigasleep.ui.components.StatusChip
import com.rbmr.taigasleep.ui.components.StatusKind
import com.rbmr.taigasleep.ui.components.TaigaCard
import com.rbmr.taigasleep.ui.theme.taiga
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The server ping of the address field, separate from the login that follows it. */
private sealed interface AddressPing {
  data object Idle : AddressPing

  data object Checking : AddressPing

  data class Reachable(val baseUrl: String) : AddressPing

  data class Failed(val error: RemoteError) : AddressPing
}

/**
 * Settings → Remote. Connecting to the server and logging in are two separate steps: opening the
 * screen (and resuming it) checks health and then the stored token, and the address field pings the
 * backend when it loses focus. The status card, the field and the login button read that one shared
 * state, so they never disagree. Nothing here blocks recording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(onBack: () -> Unit) {
  val context = LocalContext.current
  val repository = remember { RemoteRepository.of(context) }
  val status by repository.status.collectAsStateWithLifecycle()
  val checking by repository.checking.collectAsStateWithLifecycle()

  var url by rememberSaveable { mutableStateOf(repository.savedUrl) }
  var email by rememberSaveable { mutableStateOf(repository.savedEmail) }
  var password by rememberSaveable { mutableStateOf("") }
  var passwordVisible by rememberSaveable { mutableStateOf(false) }
  var showLogout by rememberSaveable { mutableStateOf(false) }
  var result by remember { mutableStateOf<CheckResult.Failure?>(null) }
  var ping by remember { mutableStateOf<AddressPing>(AddressPing.Idle) }
  var pingGeneration by remember { mutableIntStateOf(0) }
  var urlWasFocused by remember { mutableStateOf(false) }
  val scope = rememberCoroutineScope()

  // Pings the server of the address field; invalid addresses are reported without a request.
  fun pingServer() {
    val raw = url
    if (raw.isBlank()) {
      ping = AddressPing.Idle
      return
    }
    val parsed = RemoteUrl.parse(raw)
    val address = parsed.getOrNull()
    if (address == null) {
      ping =
          AddressPing.Failed(
              (parsed.exceptionOrNull() as? RemoteUrlException)?.error ?: RemoteError.InvalidUrl
          )
      return
    }
    val current = ping
    if (current is AddressPing.Reachable && current.baseUrl == address.baseUrl) return

    val generation = ++pingGeneration
    ping = AddressPing.Checking
    scope.launch {
      val checked = repository.checkAddress(raw)
      if (generation != pingGeneration) return@launch
      ping =
          when (checked) {
            is AddressCheck.Reachable -> AddressPing.Reachable(checked.baseUrl)
            is AddressCheck.Failure -> AddressPing.Failed(checked.error)
          }
    }
  }

  // Both checks on open and on resume: health, then the saved token when health passed. The
  // shared state never depends on when this screen was last composed.
  fun checkRemote() {
    val raw = url
    if (raw.isBlank()) {
      ping = AddressPing.Idle
      return
    }
    val generation = ++pingGeneration
    ping = AddressPing.Checking
    scope.launch {
      val checked = repository.check(raw)
      if (generation != pingGeneration) return@launch
      ping =
          when (checked) {
            is AddressCheck.Reachable -> AddressPing.Reachable(checked.baseUrl)
            is AddressCheck.Failure -> AddressPing.Failed(checked.error)
          }
    }
  }

  LifecycleResumeEffect(Unit) {
    checkRemote()
    onPauseOrDispose {}
  }

  val parsedBase = RemoteUrl.parse(url).getOrNull()?.baseUrl
  val reachableBase = (ping as? AddressPing.Reachable)?.baseUrl
  val addressOk = reachableBase != null && reachableBase == parsedBase
  val passwordless =
      addressOk &&
          password.isEmpty() &&
          repository.hasSession &&
          parsedBase == repository.savedUrl &&
          email.trim().equals(repository.savedEmail, ignoreCase = true)
  val canLogin =
      !checking && addressOk && email.isNotBlank() && (password.isNotEmpty() || passwordless)
  val passwordHint =
      when {
        password.isNotEmpty() -> null
        passwordless -> R.string.remote_password_keep
        addressOk -> R.string.remote_password_required
        else -> null
      }

  BackHandler(onBack = onBack)

  Scaffold(
      containerColor = MaterialTheme.colorScheme.background,
      topBar = {
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
      },
  ) { padding ->
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
      item(key = "header") {
        Column(Modifier.padding(horizontal = 4.dp)) {
          Text(
              text = stringResource(R.string.remote_kicker),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(Modifier.height(4.dp))
          Text(
              text = stringResource(R.string.remote_title),
              style = MaterialTheme.typography.displayLarge,
          )
          Spacer(Modifier.height(8.dp))
          Text(
              text = stringResource(R.string.remote_intro),
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      item(key = "status") { StatusCard(status) }

      item(key = "address") {
        Column {
          SectionLabel(
              stringResource(R.string.remote_group_address),
              Modifier.padding(bottom = 8.dp),
          )
          TaigaCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              val fieldError = (ping as? AddressPing.Failed)?.error
              OutlinedTextField(
                  value = url,
                  onValueChange = {
                    url = it
                    // A new address invalidates the previous ping and disables the login button.
                    pingGeneration++
                    ping = AddressPing.Idle
                  },
                  modifier =
                      Modifier.fillMaxWidth().onFocusChanged { focus ->
                        if (urlWasFocused && !focus.isFocused) pingServer()
                        urlWasFocused = focus.isFocused
                      },
                  label = { Text(stringResource(R.string.remote_url_label)) },
                  placeholder = { Text(stringResource(R.string.remote_url_placeholder)) },
                  singleLine = true,
                  isError = fieldError != null,
                  shape = RoundedCornerShape(16.dp),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                  trailingIcon = {
                    when (val state = ping) {
                      AddressPing.Checking ->
                          CircularProgressIndicator(
                              modifier = Modifier.size(20.dp),
                              strokeWidth = 2.dp,
                          )
                      is AddressPing.Reachable ->
                          Icon(
                              imageVector = Icons.Outlined.CheckCircle,
                              contentDescription = stringResource(R.string.remote_address_ok),
                              tint = MaterialTheme.taiga.ok,
                          )
                      is AddressPing.Failed ->
                          Icon(
                              imageVector = Icons.Outlined.ErrorOutline,
                              contentDescription = stringResource(R.string.remote_address_failed),
                              tint = MaterialTheme.taiga.bad,
                          )
                      AddressPing.Idle -> Unit
                    }
                  },
                  supportingText = fieldError?.let { error -> { Text(errorMessage(error)) } },
              )
              OutlinedTextField(
                  value = email,
                  onValueChange = { email = it },
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text(stringResource(R.string.remote_email_label)) },
                  singleLine = true,
                  shape = RoundedCornerShape(16.dp),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
              )
              OutlinedTextField(
                  value = password,
                  onValueChange = { password = it },
                  modifier = Modifier.fillMaxWidth(),
                  label = { Text(stringResource(R.string.remote_password_label)) },
                  singleLine = true,
                  shape = RoundedCornerShape(16.dp),
                  visualTransformation =
                      if (passwordVisible) VisualTransformation.None
                      else PasswordVisualTransformation(),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                  trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
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
                  supportingText = passwordHint?.let { hint -> { Text(stringResource(hint)) } },
              )
            }
          }
        }
      }

      // Below the fields so that it never moves them while the address is being typed.
      item(key = "login-action") {
        // Step two only: the address has already been pinged above.
        PrimaryButton(
            text =
                stringResource(
                    when {
                      checking -> R.string.remote_checking
                      passwordless -> R.string.remote_check_login
                      else -> R.string.remote_login
                    }
                ),
            onClick = {
              scope.launch {
                val outcome =
                    if (passwordless) {
                      // No password means "check the stored token", like the automatic check.
                      repository.verify()
                          ?: CheckResult.Failure(RemoteStep.LOGIN, RemoteError.MissingCredentials)
                    } else {
                      repository.login(url, email, password)
                    }
                result = outcome as? CheckResult.Failure
                if (outcome is CheckResult.Success) {
                  password = ""
                } else if (outcome is CheckResult.Failure &&
                    repository.status.value is RemoteStatus.Unreachable) {
                  // The server stopped answering between the check and the login.
                  pingGeneration++
                  ping = AddressPing.Failed(outcome.error)
                }
              }
            },
            enabled = canLogin,
            size = ButtonSize.Large,
        )
      }

      result?.let { checked ->
        item(key = "result") { CheckResultBanner(checked) }
      }

      if (status is RemoteStatus.Connected || repository.hasSession) {
        item(key = "logout") {
          SecondaryButton(
              text = stringResource(R.string.remote_logout),
              onClick = { showLogout = true },
              modifier = Modifier.fillMaxWidth(),
              size = ButtonSize.Large,
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
                result = null
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

@Composable
private fun StatusCard(status: RemoteStatus) {
  val chipKind =
      when (status) {
        RemoteStatus.NotConfigured -> StatusKind.Warn
        is RemoteStatus.Connected -> StatusKind.Ok
        is RemoteStatus.LoginNeeded -> StatusKind.Warn
        is RemoteStatus.Unreachable -> StatusKind.Bad
      }
  val chipLabel =
      when (status) {
        RemoteStatus.NotConfigured -> stringResource(R.string.remote_state_not_configured)
        is RemoteStatus.Connected -> stringResource(R.string.remote_state_connected_short)
        is RemoteStatus.LoginNeeded -> stringResource(R.string.remote_state_login_needed_short)
        is RemoteStatus.Unreachable -> stringResource(R.string.remote_state_unreachable_short)
      }
  val checkedAt =
      when (status) {
        RemoteStatus.NotConfigured -> null
        is RemoteStatus.Connected -> status.checkedAtMs
        is RemoteStatus.LoginNeeded -> status.checkedAtMs
        is RemoteStatus.Unreachable -> status.checkedAtMs
      }
  // Keep the relative time honest while the screen is open; checks refresh it too.
  var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(checkedAt) {
    if (checkedAt == null) return@LaunchedEffect
    nowMs = System.currentTimeMillis()
    while (true) {
      delay(1_000)
      nowMs = System.currentTimeMillis()
    }
  }
  TaigaCard {
    StatusChip(kind = chipKind, label = chipLabel)
    Spacer(Modifier.height(8.dp))
    Text(
        text = lastCheck(checkedAt, nowMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun CheckResultBanner(failure: CheckResult.Failure) {
  StatusBanner(
      kind = StatusKind.Bad,
      title = stringResource(R.string.remote_login_failed_title),
      body = errorMessage(failure.error),
  )
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
      is RemoteError.Http -> stringResource(R.string.remote_error_http, error.status)
      RemoteError.MalformedResponse -> stringResource(R.string.remote_error_malformed)
    }

@Composable
private fun lastCheck(checkedAt: Long?, nowMs: Long): String {
  if (checkedAt == null) return stringResource(R.string.remote_last_check_never)
  val relative =
      DateUtils.getRelativeTimeSpanString(
          checkedAt,
          nowMs,
          DateUtils.SECOND_IN_MILLIS,
          DateUtils.FORMAT_ABBREV_RELATIVE,
      )
  return stringResource(R.string.remote_last_check, relative.toString())
}
