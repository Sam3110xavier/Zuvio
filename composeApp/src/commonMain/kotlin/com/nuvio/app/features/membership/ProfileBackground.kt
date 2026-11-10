package com.nuvio.app.features.membership

import com.nuvio.app.features.profiles.NuvioProfile

sealed interface ProfileBackgroundSelection {
    data class Custom(val url: String) : ProfileBackgroundSelection
}

fun resolveProfileBackground(
    profile: NuvioProfile,
    entitlements: CosmeticEntitlements,
): ProfileBackgroundSelection? =
    profile.profileBackgroundUrl?.trim()?.takeIf { it.isNotBlank() }
        ?.let(ProfileBackgroundSelection::Custom)
