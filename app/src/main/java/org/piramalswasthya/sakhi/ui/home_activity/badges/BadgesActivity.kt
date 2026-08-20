package org.piramalswasthya.sakhi.ui.home_activity.badges

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.piramalswasthya.sakhi.BuildConfig
import org.piramalswasthya.sakhi.R
import org.piramalswasthya.sakhi.database.room.InAppDb
import org.piramalswasthya.sakhi.database.shared_preferences.PreferenceDao
import org.piramalswasthya.sakhi.helpers.BadgeDebugSeeder
import org.piramalswasthya.sakhi.helpers.BadgeGates
import org.piramalswasthya.sakhi.helpers.MyContextWrapper
import org.piramalswasthya.sakhi.model.BadgeIds
import org.piramalswasthya.sakhi.repositories.BadgeRepository
import timber.log.Timber
import javax.inject.Inject

/**
 * "My Badges" — one card per badge: current tier art, an animated progress ring
 * toward the next tier, and the 4-tier ladder with locked tiers desaturated in
 * code (no extra art needed).
 *
 * Built programmatically and sized for 40-55+ users on small, low-brightness
 * phones: large art, large type, generous spacing. Reads only persisted badge
 * rows, so the screen opens instantly and never waits on evaluation.
 */
@AndroidEntryPoint
class BadgesActivity : AppCompatActivity() {

    @Inject
    lateinit var badgeRepository: BadgeRepository

    @Inject
    lateinit var database: InAppDb

    /** View handles for one badge card. */
    private class BadgeCard(
        val hero: ImageView,
        val status: TextView,
        val ring: BadgeProgressRingView,
        val progressText: TextView,
        val nextTier: TextView,
        val tiers: List<ImageView>,
    )

    private lateinit var steadySyncerCard: BadgeCard
    private lateinit var timelyReporterCard: BadgeCard
    private lateinit var completeWorkerCard: BadgeCard
    private var debugEvidenceText: TextView? = null

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(value: Int) = (value * density).toInt()

    private val lockedFilter =
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BadgeLanguageEntryPoint {
        val pref: PreferenceDao
    }

    /**
     * Same per-app language wrapping every other activity does (HomeActivity
     * pattern): without this the badge screen would ignore the ASHA's chosen
     * language and fall back to the system locale.
     */
    override fun attachBaseContext(newBase: Context) {
        val pref = EntryPointAccessors
            .fromApplication(newBase, BadgeLanguageEntryPoint::class.java).pref
        super.attachBaseContext(
            MyContextWrapper.wrap(
                newBase,
                newBase.applicationContext,
                pref.getCurrentLanguage().symbol
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        supportActionBar?.title = getString(R.string.badges_title)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ─── UI construction ─────────────────────────────────────────────────────

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
            setBackgroundColor(Color.parseColor("#FAF7FF"))
        }

        val (steadyView, steady) = buildCard(
            BadgeIds.STEADY_SYNCER, getString(R.string.badge_steady_syncer)
        )
        steadySyncerCard = steady
        column.addView(steadyView)

        val (timelyView, timely) = buildCard(
            BadgeIds.TIMELY_REPORTER, getString(R.string.badge_timely_reporter)
        )
        timelyReporterCard = timely
        column.addView(timelyView)

        val (cwView, cw) = buildCard(
            BadgeIds.COMPLETE_WORKER, getString(R.string.badge_complete_worker)
        )
        completeWorkerCard = cw
        column.addView(cwView)

        if (BuildConfig.DEBUG) column.addView(buildDebugPanel())

        return ScrollView(this).apply { addView(column) }
    }

    private fun buildCard(badgeId: String, name: String): Pair<View, BadgeCard> {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(2).toFloat()
        }

        val hero = ImageView(this)
        card.addView(hero, LinearLayout.LayoutParams(dp(200), dp(200)))

        card.addView(TextView(this).apply {
            text = name
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#2A1E5C"))
            gravity = Gravity.CENTER
        })

        val status = TextView(this).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#453C6E"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(12))
        }
        card.addView(status)

        // Ring with the count centred inside it.
        val ring = BadgeProgressRingView(this)
        val progressText = TextView(this).apply {
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#F57C00"))
            gravity = Gravity.CENTER
        }
        val ringFrame = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER))
            addView(
                progressText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
        card.addView(ringFrame, LinearLayout.LayoutParams(dp(130), dp(130)))

        val nextTier = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#453C6E"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(16))
        }
        card.addView(nextTier)

        // The 4-tier ladder: earned tiers in colour, locked tiers desaturated.
        val ladder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val tiers = (1..4).map { tier ->
            ImageView(this).apply {
                setImageResource(BadgeCeremonyDialogFragment.tierDrawable(badgeId, tier))
            }.also {
                ladder.addView(
                    it,
                    LinearLayout.LayoutParams(dp(70), dp(70))
                        .apply { setMargins(dp(4), 0, dp(4), 0) }
                )
            }
        }
        card.addView(ladder)

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(20))
            addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        return wrapper to BadgeCard(hero, status, ring, progressText, nextTier, tiers)
    }

    /** Demo controls (debug builds only): fabricate timelines, then re-evaluate. */
    private fun buildDebugPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(TextView(this).apply {
            text = "Demo (debug only)"
            textSize = 12f
            setTextColor(Color.parseColor("#9A93B8"))
        })
        debugEvidenceText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.parseColor("#453C6E"))
            setPadding(0, dp(6), 0, dp(6))
        }
        panel.addView(debugEvidenceText)

        fun demoButton(label: String, scenario: suspend () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                isEnabled = false
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        scenario()
                        val awards = badgeRepository.onAppOpened(BadgeGates.isBadgesEnabled(this@BadgesActivity))
                        withContext(Dispatchers.Main) {
                            isEnabled = true
                            refresh()
                            showCeremonyFor(awards)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Badge demo seed failed")
                        withContext(Dispatchers.Main) { isEnabled = true }
                    }
                }
            }
        }

        panel.addView(demoButton("Sync: 3 of 4 weeks") {
            BadgeDebugSeeder.seedThreeOfFourWeeks(database, preferenceUserId())
        })
        panel.addView(demoButton("Sync: earn Tier 1 now") {
            BadgeDebugSeeder.seedEarnTierNow(database, preferenceUserId(), weeksOfSyncs = 4)
        })
        panel.addView(demoButton("Sync: earn Tier 4 now") {
            BadgeDebugSeeder.seedEarnTierNow(database, preferenceUserId(), weeksOfSyncs = 26)
        })
        panel.addView(demoButton("Complete Worker: earn this quarter") {
            val cal = java.util.Calendar.getInstance()
            val q = (cal.get(java.util.Calendar.MONTH) / 3) + 1
            BadgeDebugSeeder.seedCompleteWorkerQuarter(
                database, preferenceUserId(), cal.get(java.util.Calendar.YEAR), q
            )
        })
        return panel
    }

    private fun showCeremonyFor(awards: List<BadgeRepository.NewTierAward>) {
        // One ceremony at a time: show the highest tier newly earned.
        val top = awards.maxByOrNull { it.tier } ?: return
        if (supportFragmentManager
                .findFragmentByTag(BadgeCeremonyDialogFragment.TAG) == null
        ) {
            BadgeCeremonyDialogFragment.newInstance(top.badgeId, top.tier)
                .show(supportFragmentManager, BadgeCeremonyDialogFragment.TAG)
        }
    }

    private suspend fun preferenceUserId(): Int =
        withContext(Dispatchers.IO) { badgeRepository.requireUserIdForDebug() }

    // ─── Binding ─────────────────────────────────────────────────────────────

    private fun refresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            val syncStatus = badgeRepository.getSteadySyncerStatus()
            val timelyStatus = badgeRepository.getTimelyReporterStatus()
            val cwStatus = badgeRepository.getCompleteWorkerStatus()
            val evidence =
                if (BuildConfig.DEBUG) badgeRepository.getDebugEvidenceSummary() else null
            withContext(Dispatchers.Main) {
                bindSteadySyncer(syncStatus)
                bindTimelyReporter(timelyStatus)
                bindCompleteWorker(cwStatus)
                evidence?.let { debugEvidenceText?.text = it }
            }
        }
    }

    private fun bindSteadySyncer(status: BadgeRepository.SteadySyncerStatus) {
        val card = steadySyncerCard
        bindArt(card, BadgeIds.STEADY_SYNCER, status.highestTierEarned)

        // Warm, mechanics-free copy only: never surface grace counts or "broken"
        // framing to the ASHA (concept-note rule: a quiet stretch is never failure).
        card.status.text = when {
            !status.runAlive && status.highestTierEarned == 0 ->
                getString(R.string.badge_streak_not_started)
            !status.runAlive -> getString(R.string.badge_streak_paused)
            else -> getString(R.string.badge_streak_alive)
        }
        bindProgress(
            card, status.advanceCount, status.nextTierThreshold, R.string.badge_next_tier
        )
    }

    private fun bindTimelyReporter(status: BadgeRepository.TimelyReporterStatus) {
        val card = timelyReporterCard
        bindArt(card, BadgeIds.TIMELY_REPORTER, status.highestTierEarned)

        card.status.text = when {
            status.currentStreak == 0 && status.highestTierEarned == 0 ->
                getString(R.string.badge_timely_not_started)
            status.currentStreak == 0 -> getString(R.string.badge_streak_paused)
            else -> getString(R.string.badge_streak_alive)
        }
        bindProgress(
            card, status.currentStreak, status.nextTierThreshold,
            R.string.badge_next_tier_months
        )
    }

    /**
     * Complete Worker is quarterly, not laddered: the hero art is THIS quarter's
     * badge (Q1..Q4), coloured once earned and greyscale until then, and the
     * ladder row shows all four quarters with the ones she has earned lit up.
     */
    private fun bindCompleteWorker(status: BadgeRepository.CompleteWorkerStatus) {
        val card = completeWorkerCard
        val q = status.currentQuarterNumber

        card.hero.setImageResource(
            BadgeCeremonyDialogFragment.tierDrawable(BadgeIds.COMPLETE_WORKER, q)
        )
        card.hero.colorFilter = if (status.earnedThisQuarter) null else lockedFilter
        card.hero.alpha = if (status.earnedThisQuarter) 1f else 0.55f

        // Only the CURRENT quarter is lit, because that is the one she can act on
        // now; past quarters are shown greyed so the row reads as a year at a
        // glance without implying the others are still winnable.
        card.tiers.forEachIndexed { index, view ->
            val isCurrent = (index + 1) == q
            val lit = isCurrent && status.earnedThisQuarter
            view.colorFilter = if (lit) null else lockedFilter
            view.alpha = if (lit) 1f else if (isCurrent) 0.55f else 0.35f
        }

        card.status.text = if (status.earnedThisQuarter) {
            getString(R.string.badge_complete_worker_earned)
        } else {
            getString(R.string.badge_complete_worker_progress)
        }

        card.ring.setProgress(status.domainsActive, status.domainsRequired)
        card.progressText.text = getString(
            R.string.badge_progress_weeks, status.domainsActive, status.domainsRequired
        )
        card.nextTier.text = if (status.earnedThisQuarter) {
            getString(R.string.badge_complete_worker_next_quarter)
        } else {
            getString(R.string.badge_complete_worker_domains_needed, status.domainsRequired)
        }
        card.nextTier.visibility = View.VISIBLE
    }

    private fun bindArt(card: BadgeCard, badgeId: String, earned: Int) {
        card.hero.setImageResource(
            BadgeCeremonyDialogFragment.tierDrawable(badgeId, if (earned == 0) 1 else earned)
        )
        card.hero.colorFilter = if (earned == 0) lockedFilter else null
        card.hero.alpha = if (earned == 0) 0.55f else 1f

        card.tiers.forEachIndexed { index, view ->
            val unlocked = earned >= index + 1
            view.colorFilter = if (unlocked) null else lockedFilter
            view.alpha = if (unlocked) 1f else 0.45f
        }
    }

    private fun bindProgress(
        card: BadgeCard,
        current: Int,
        target: Int?,
        nextTierStringRes: Int,
    ) {
        if (target != null) {
            card.ring.setProgress(current, target)
            card.progressText.text = getString(R.string.badge_progress_weeks, current, target)
            card.nextTier.text = getString(nextTierStringRes, target)
            card.nextTier.visibility = View.VISIBLE
        } else {
            card.ring.setProgress(1, 1)
            card.progressText.text = ""
            card.nextTier.text = getString(R.string.badge_all_tiers_done)
            card.nextTier.visibility = View.VISIBLE
        }
    }
}
