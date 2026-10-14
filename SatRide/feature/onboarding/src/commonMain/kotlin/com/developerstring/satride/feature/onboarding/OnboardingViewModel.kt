package com.developerstring.satride.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.datastore.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Onboarding's only job besides being charming: remember that it happened.
 *
 * The flag is written before [onDone] runs so a crash between the two shows onboarding again
 * (harmless) rather than never marking it complete (annoying forever).
 */
class OnboardingViewModel(private val settings: SettingsRepository) : ViewModel() {

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            onDone()
        }
    }
}
