package com.rbmr.taigasleep.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rbmr.taigasleep.appContainer
import com.rbmr.taigasleep.backend.AccessTokenDto
import com.rbmr.taigasleep.backend.TaigaClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the Access tokens page draws: the tokens of the account, what the page is waiting on,
 * the failure of the last call, and the token of the last creation.
 */
data class AccessTokensUiState(
    val tokens: List<AccessTokenDto> = emptyList(),
    val loading: Boolean = false,
    /** A create or a revoke is running. */
    val busy: Boolean = false,
    /**
     * The secret of the token that was just created. The server cannot show it again, so it stays
     * here only until the user dismisses it.
     */
    val created: String? = null,
    val error: String? = null,
    /** False when no account is logged in, which is why there is nothing to list. */
    val signedIn: Boolean = true,
)

/**
 * The state holder of Settings → Server → Access tokens. The tokens are the server's, so it keeps
 * no copy that survives a refresh: it asks, shows the answer and forwards create and revoke.
 */
class AccessTokensViewModel(
    private val client: () -> TaigaClient?,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val _state = MutableStateFlow(AccessTokensUiState())
  val state: StateFlow<AccessTokensUiState> = _state.asStateFlow()

  /** Reads the token list, or reports that there is no account to read it for. */
  fun refresh() {
    val api = client()
    if (api == null) {
      _state.update {
        it.copy(tokens = emptyList(), loading = false, busy = false, error = null, signedIn = false)
      }
      return
    }
    _state.update { it.copy(loading = true, error = null, signedIn = true) }
    launch {
      val tokens = api.listAccessTokens().items
      _state.update { it.copy(tokens = tokens, loading = false) }
    }
  }

  /**
   * Creates a token with [name]. The answer carries its secret, which is kept until
   * [dismissCreated] so the page can show it once.
   */
  fun create(name: String) {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return
    val api = client()
    if (api == null) {
      _state.update { it.copy(signedIn = false) }
      return
    }
    _state.update { it.copy(busy = true, error = null) }
    launch {
      val created = api.createAccessToken(trimmed)
      _state.update {
        it.copy(
            tokens = listOf(created.copy(token = null)) + it.tokens,
            busy = false,
            created = created.token,
        )
      }
    }
  }

  /** Revokes one token; it leaves the list once the server accepted the revoke. */
  fun revoke(id: String) {
    val api = client() ?: return
    _state.update { it.copy(busy = true, error = null) }
    launch {
      api.deleteAccessToken(id)
      _state.update {
        it.copy(tokens = it.tokens.filterNot { token -> token.id == id }, busy = false)
      }
    }
  }

  /** The user has seen and copied the new token; the page stops holding it. */
  fun dismissCreated() {
    _state.update { it.copy(created = null) }
  }

  override fun onCleared() {
    scope.cancel()
  }

  /**
   * Runs one call and turns a failure into the page's error, so every action ends in a state the
   * page can draw whether it worked or not.
   */
  private fun launch(block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
      } catch (e: Exception) {
        _state.update {
          it.copy(loading = false, busy = false, error = e.message ?: e::class.java.simpleName)
        }
      }
    }
  }

  /** Builds the view model from the account the app is logged in with. */
  class Factory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AccessTokensViewModel(client = { context.appContainer.remoteRepository.api() }) as T
  }
}
