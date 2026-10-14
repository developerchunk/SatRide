package com.developerstring.satride.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.developerstring.satride.core.designsystem.component.GlassPanel
import com.developerstring.satride.core.designsystem.component.SatRideWideButton
import com.developerstring.satride.core.designsystem.theme.SatRideTheme
import com.developerstring.satride.core.designsystem.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sign in with an email and a password.
 *
 * This exists for **Google's reviewers and the internal test team**, and the screen says so rather
 * than pretending otherwise. There is no registration form anywhere in SatRide, so the only
 * accounts that work here are ones created by hand in the Firebase console — a member of the
 * public who finds this screen has nothing to type into it, and being told that is better than
 * being left to guess.
 *
 * It is reachable from Settings rather than hidden behind a tap-seven-times gesture, because the
 * access is *declared* to Google. An undocumented back door found during review is a problem; a
 * labelled door described in the App access form is not.
 *
 * No second factor, no email link, no one-time code — see [AuthRepository.signInWithEmail].
 */
@Composable
fun TesterSignInScreen(
    onSignedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TesterSignInViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedIn) { if (state.signedIn) onSignedIn() }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val minHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .heightIn(min = minHeight)
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md, Alignment.CenterVertically),
        ) {
            // Capped, so the form does not stretch into a single absurd line on a tablet or in
            // landscape.
            Column(
                modifier = Modifier.widthIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text("Tester sign-in", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "For app reviewers and the SatRide test team. Accounts are issued by " +
                        "hand — there is nothing to sign up for here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                GlassPanel(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = viewModel::onEmailChanged,
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !state.busy,
                        isError = state.error != null,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Box(Modifier.padding(top = Spacing.sm)) {
                        // Local to the field, not the view model: whether the password is masked is
                        // a display choice that never leaves this screen, and keeping it out of the
                        // shared state means the character stream is the only thing the view model
                        // ever holds.
                        var revealed by remember { mutableStateOf(false) }
                        OutlinedTextField(
                            value = state.password,
                            onValueChange = viewModel::onPasswordChanged,
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !state.busy,
                            isError = state.error != null,
                            visualTransformation = if (revealed) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                // A word rather than an eye glyph: the icon set has no eye, and
                                // "Show"/"Hide" is unambiguous where a crossed-out eye is a coin
                                // toss on which state it represents.
                                TextButton(
                                    onClick = { revealed = !revealed },
                                    enabled = !state.busy,
                                ) {
                                    Text(
                                        text = if (revealed) "Hide" else "Show",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                state.error?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = SatRideTheme.accents.danger,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.busy) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = SatRideTheme.accents.tracking,
                            modifier = Modifier.padding(Spacing.sm),
                        )
                    }
                } else {
                    SatRideWideButton(text = "Sign in", onClick = viewModel::submit)
                }

                TextButton(onClick = onSignedIn, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Back",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
