package org.piramalswasthya.sakhi.helpers

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.piramalswasthya.sakhi.database.shared_preferences.PreferenceDao
import timber.log.Timber

/**
 * Feature gate for the badges mechanic. Backed by the shared
 * [GamificationConfigProvider] (one Remote Config provider for every
 * gamification mechanic — see its own doc for the design).
 *
 * Debug builds force ON so local dev/demo/seeding is unaffected. Release
 * builds are FAIL CLOSED until the Firebase console turns
 * `gamification_master_enabled` + `badges_enabled` on for this ASHA.
 *
 * Callers are plain functions/objects (HomeActivity's static call sites,
 * the debug seeder panel) rather than Hilt-injected classes, so this exposes
 * a Context-based entry point via EntryPointAccessors — the same pattern
 * BadgesActivity already uses for per-app language.
 */
object BadgeGates {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BadgeGateEntryPoint {
        val configProvider: GamificationConfigProvider
        val pref: PreferenceDao
    }

    fun isBadgesEnabled(context: Context): Boolean = try {
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext, BadgeGateEntryPoint::class.java
        )
        val userId = entryPoint.pref.getLoggedInUser()?.userId
        entryPoint.configProvider.isMechanicEnabled(
            GamificationConfigProvider.Mechanic.BADGES, userId
        )
    } catch (e: Exception) {
        // Called synchronously from HomeActivity.onResume — a gate failure must
        // fail closed and never take the clinical app down with it.
        Timber.e(e, "BadgeGates: gate check failed, defaulting to disabled")
        false
    }
}
