package com.developerstring.satride.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the user told us about themselves.
 *
 * Distinct from [SatRideUser], which is what Google told us. The separation matters: a person can
 * change their SatRide name without touching their Google account, and the app should show the name
 * they chose here rather than the one on their email.
 *
 * Only [displayName] is required. Country and referral are both optional and both stay optional —
 * a sign-up form that demands more than it needs is the fastest way to lose someone who was only
 * mildly curious.
 *
 * Field names are the Firestore document's field names; the security rules in
 * `docs/plan/08-firestore-rules.md` name them explicitly, so renaming one here without changing the
 * rules would start failing writes at runtime with a permission error.
 */
@Serializable
data class UserProfile(
    @SerialName("displayName") val displayName: String,
    /** ISO 3166-1 alpha-2, uppercase. Null when the user did not say. */
    @SerialName("countryCode") val countryCode: String? = null,
    /** Free text the user typed. Nothing resolves it yet — see the rules doc. */
    @SerialName("referredBy") val referredBy: String? = null,
) {
    companion object {
        const val MAX_NAME_LENGTH: Int = 60
        const val MAX_REFERRAL_LENGTH: Int = 60

        /**
         * Cleans up what a text field produced.
         *
         * Trimming is not cosmetic here: a trailing space from a soft keyboard's autocomplete would
         * otherwise be stored, and then the name renders with a gap the user cannot see or remove.
         * Blank optional fields become null rather than empty strings so that "not answered" has
         * exactly one representation in Firestore instead of two.
         */
        fun of(displayName: String, countryCode: String?, referredBy: String?): UserProfile = UserProfile(
            displayName = displayName.trim().take(MAX_NAME_LENGTH),
            countryCode = countryCode?.trim()?.uppercase()?.takeIf { it.length == 2 },
            referredBy = referredBy?.trim()?.take(MAX_REFERRAL_LENGTH)?.takeIf { it.isNotEmpty() },
        )
    }

    val isValid: Boolean get() = displayName.isNotBlank() && displayName.length <= MAX_NAME_LENGTH
}

/** Why a profile could not be saved. */
sealed interface ProfileFailure {
    data object NotSignedIn : ProfileFailure

    data class Invalid(val reason: String) : ProfileFailure

    data class Network(val cause: Throwable?) : ProfileFailure

    data class Unexpected(val cause: Throwable?) : ProfileFailure
}

fun ProfileFailure.userMessage(): String = when (this) {
    ProfileFailure.NotSignedIn -> "Sign in first to save a profile."

    is ProfileFailure.Invalid -> reason

    // Firestore queues the write and sends it when there is a connection, so this is genuinely
    // reassurance rather than an apology.
    is ProfileFailure.Network -> "Saved on this device. It will sync when you are back online."

    is ProfileFailure.Unexpected -> "Could not save your profile. Please try again."
}
