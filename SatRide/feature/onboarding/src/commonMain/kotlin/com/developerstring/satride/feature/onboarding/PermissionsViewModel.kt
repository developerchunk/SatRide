package com.developerstring.satride.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.developerstring.satride.core.datastore.SettingsRepository
import kotlinx.coroutines.launch

/**
 * The permission screen's only job besides asking: remember that it asked.
 *
 * What is recorded is that the screen was *shown*, not what the user answered. The answers belong
 * to the platform and change without the app being told — a cached "granted" survives the user
 * revoking it in system settings, and a cached "denied" would hide a permission they since turned
 * on. Every reader goes to the platform for the current answer; this flag exists only so the
 * screen is never put in front of the same person twice.
 */
class PermissionsViewModel(private val settings: SettingsRepository) : ViewModel() {

    fun complete(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setPermissionsPrimed(true)
            onDone()
        }
    }
}
