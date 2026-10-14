package com.developerstring.satride.feature.auth

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class TesterSignInUiState(
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
)

class TesterSignInViewModel(private val auth: AuthRepository) : ViewModel() {

    private val mutableState = MutableStateFlow(TesterSignInUiState())
    val state: StateFlow<TesterSignInUiState> = mutableState.asStateFlow()

    fun onEmailChanged(value: String) = mutableState.update { it.copy(email = value, error = null) }

    fun onPasswordChanged(value: String) = mutableState.update { it.copy(password = value, error = null) }

    fun submit() {
        val current = mutableState.value
        if (current.busy) return
        if (current.email.isBlank() || current.password.isBlank()) {
            mutableState.update { it.copy(error = "Enter both an email and a password.") }
            return
        }

        mutableState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            auth.signInWithEmail(current.email, current.password)
                .onSuccess {
                    // The password is dropped from state on success as well as on failure. It has
                    // no further use, and a view model that outlives the screen holding one is a
                    // credential sitting in memory for no reason.
                    mutableState.update {
                        it.copy(busy = false, password = "", signedIn = true)
                    }
                }
                .onFailure { cause ->
                    mutableState.update {
                        it.copy(busy = false, password = "", error = messageFor(cause))
                    }
                }
        }
    }

    /**
     * Turns a Firebase failure into something readable.
     *
     * Deliberately does not distinguish "no such account" from "wrong password". The distinction
     * is only useful to somebody guessing which emails are registered, and this screen's real
     * users have been handed both halves already.
     */
    private fun messageFor(cause: Throwable): String {
        val text = cause.message.orEmpty()
        return when {
            text.contains("network", ignoreCase = true) ->
                "No connection. Check the network and try again."

            text.contains("blocked", ignoreCase = true) || text.contains("too many", ignoreCase = true) ->
                "Too many attempts. Wait a minute and try again."

            else -> "That email and password did not work."
        }
    }
}
