package org.piramalswasthya.sakhi.ui.home_activity.badges

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.fragment.app.DialogFragment
import org.piramalswasthya.sakhi.R
import org.piramalswasthya.sakhi.model.BadgeIds

/**
 * The award ceremony (S6): a full-screen, ~3s, tap-to-skip takeover. Layered
 * premium feel on low-end budgets — scrim fade, badge scale-in with overshoot,
 * a radial glow pulse, one-shot code-drawn confetti, a double haptic pulse, and
 * warm personal copy. Every animation is finite; reduced-motion devices get a
 * static reveal. Built programmatically: the ceremony owns no XML layout.
 */
class BadgeCeremonyDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "BadgeCeremonyDialog"

        /** Mirrors CompleteWorkerEngine's default; used only for ceremony copy. */
        private const val COMPLETE_WORKER_DOMAINS_REQUIRED = 3
        private const val ARG_TIER = "tier"
        private const val ARG_BADGE_ID = "badgeId"

        /** Milestone counts per badge, indexed by tier — weeks / months. */
        private val TIER_UNITS = mapOf(
            BadgeIds.STEADY_SYNCER to mapOf(1 to 4, 2 to 8, 3 to 16, 4 to 26),
            BadgeIds.TIMELY_REPORTER to mapOf(1 to 2, 2 to 4, 3 to 6, 4 to 12),
        )

        /**
         * Complete Worker has no tier ladder — it is re-earned each calendar
         * quarter, and `tier` carries the QUARTER NUMBER (1..4) so the art can
         * show the quarter she earned it for.
         */
        private val QUARTERLY_BADGES = setOf(BadgeIds.COMPLETE_WORKER)

        fun newInstance(badgeId: String, tier: Int) = BadgeCeremonyDialogFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_BADGE_ID, badgeId)
                putInt(ARG_TIER, tier)
            }
        }

        fun tierDrawable(badgeId: String, tier: Int): Int =
            if (badgeId == BadgeIds.COMPLETE_WORKER) when (tier) {
                1 -> R.drawable.badge_complete_worker_q1
                2 -> R.drawable.badge_complete_worker_q2
                3 -> R.drawable.badge_complete_worker_q3
                else -> R.drawable.badge_complete_worker_q4
            } else if (badgeId == BadgeIds.TIMELY_REPORTER) when (tier) {
                1 -> R.drawable.badge_timely_reporter_tier_1
                2 -> R.drawable.badge_timely_reporter_tier_2
                3 -> R.drawable.badge_timely_reporter_tier_3
                else -> R.drawable.badge_timely_reporter_tier_4
            } else when (tier) {
                1 -> R.drawable.badge_steady_syncer_tier_1
                2 -> R.drawable.badge_steady_syncer_tier_2
                3 -> R.drawable.badge_steady_syncer_tier_3
                else -> R.drawable.badge_steady_syncer_tier_4
            }
    }

    private var dismissible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(
            STYLE_NORMAL,
            android.R.style.Theme_Translucent_NoTitleBar_Fullscreen
        )
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val tier = requireArguments().getInt(ARG_TIER, 1)
        val badgeId = requireArguments().getString(ARG_BADGE_ID) ?: BadgeIds.STEADY_SYNCER
        val milestone =
            if (badgeId in QUARTERLY_BADGES) COMPLETE_WORKER_DOMAINS_REQUIRED
            else TIER_UNITS[badgeId]?.get(tier) ?: 4
        val subtitleRes = when {
            badgeId == BadgeIds.COMPLETE_WORKER -> R.string.badge_ceremony_subtitle_domains
            badgeId == BadgeIds.TIMELY_REPORTER -> R.string.badge_ceremony_subtitle_months
            else -> R.string.badge_ceremony_subtitle
        }

        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.parseColor("#E6120A2A")) // deep indigo scrim
            alpha = 0f
        }

        // Radial glow that pulses out from behind the badge.
        val glow = View(requireContext()).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#66FFC93C"), Color.TRANSPARENT)
            ).apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 160 * density
            }
            alpha = 0f
        }

        val badge = ImageView(requireContext()).apply {
            setImageResource(tierDrawable(badgeId, tier))
            scaleX = 0.3f
            scaleY = 0.3f
            alpha = 0f
        }

        // Type scale is deliberately large: the audience is 40-55+ ASHAs, often
        // with uncorrected presbyopia, on small low-brightness phones.
        val title = TextView(requireContext()).apply {
            text = getString(R.string.badge_ceremony_title)
            setTextColor(Color.WHITE)
            textSize = 32f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            alpha = 0f
            translationY = 24 * density
        }

        // Medium weight + generous line spacing: Devanagari/Assamese strokes are
        // thinner than Latin at equal size, and this sits on a dark scrim.
        val subtitle = TextView(requireContext()).apply {
            text = getString(subtitleRes, milestone)
            setTextColor(Color.parseColor("#F4F0FF"))
            textSize = 20f
            typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL
            )
            setLineSpacing(0f, 1.2f)
            gravity = Gravity.CENTER
            setPadding(dp(28))
            alpha = 0f
            translationY = 24 * density
        }

        // An explicit, large, high-contrast button — NOT "tap anywhere". A
        // non-technical user should never have to guess how to leave a screen,
        // and an invisible full-screen tap target is exactly that guess.
        // 56dp tall clears the accessibility minimum with room to spare.
        val continueButton = Button(requireContext()).apply {
            text = getString(R.string.badge_continue)
            textSize = 18f
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#2A1E5C"))
            background = GradientDrawable().apply {
                cornerRadius = 28 * density
                setColor(Color.parseColor("#FFC93C"))
            }
            setPadding(dp(44), dp(14), dp(44), dp(14))
            alpha = 0f
            setOnClickListener { dismissAllowingStateLoss() }
        }

        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val badgeFrame = FrameLayout(requireContext()).apply {
                addView(glow, FrameLayout.LayoutParams(dp(340), dp(340), Gravity.CENTER))
                addView(badge, FrameLayout.LayoutParams(dp(280), dp(280), Gravity.CENTER))
            }
            addView(badgeFrame, LinearLayout.LayoutParams(dp(340), dp(340)))
            addView(title)
            addView(subtitle)
            addView(
                continueButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20) }
            )
        }
        root.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val confetti = ConfettiView(requireContext())
        root.addView(
            confetti,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // Background tap also dismisses, but only as a secondary path once the
        // button is visible — the button is the affordance she is meant to see.
        root.setOnClickListener { if (dismissible) dismissAllowingStateLoss() }

        if (isReducedMotion()) {
            // Static reveal: everything visible, no animators, no confetti.
            root.alpha = 1f
            glow.alpha = 0.35f
            badge.scaleX = 1f; badge.scaleY = 1f; badge.alpha = 1f
            title.alpha = 1f; title.translationY = 0f
            subtitle.alpha = 1f; subtitle.translationY = 0f
            continueButton.alpha = 1f
            dismissible = true
        } else {
            playCeremony(root, glow, badge, title, subtitle, continueButton, confetti)
        }
        return root
    }

    private fun playCeremony(
        root: View,
        glow: View,
        badge: ImageView,
        title: View,
        subtitle: View,
        continueButton: View,
        confetti: ConfettiView,
    ) {
        fun fadeIn(view: View, delay: Long, duration: Long = 350L) =
            ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
                startDelay = delay; this.duration = duration
            }

        fun riseIn(view: View, delay: Long) =
            ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, 0f).apply {
                startDelay = delay; duration = 350L
                interpolator = DecelerateInterpolator()
            }

        val badgeIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(badge, View.SCALE_X, 0.3f, 1f),
                ObjectAnimator.ofFloat(badge, View.SCALE_Y, 0.3f, 1f),
                ObjectAnimator.ofFloat(badge, View.ALPHA, 0f, 1f),
            )
            startDelay = 150
            duration = 450
            interpolator = OvershootInterpolator(2f)
        }
        badgeIn.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                // Double haptic pulse on the reveal beat — the "weight" of the award.
                badge.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                badge.postDelayed(
                    { badge.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }, 140
                )
            }
        })

        val glowPulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(glow, View.ALPHA, 0f, 0.7f, 0f),
                ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.5f, 1.6f),
                ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.5f, 1.6f),
            )
            startDelay = 300
            duration = 900
        }

        AnimatorSet().apply {
            playTogether(
                fadeIn(root, delay = 0, duration = 200),
                badgeIn,
                glowPulse,
                fadeIn(title, delay = 500), riseIn(title, delay = 500),
                fadeIn(subtitle, delay = 650), riseIn(subtitle, delay = 650),
                fadeIn(continueButton, delay = 1_400),
            )
            start()
        }

        root.postDelayed({ confetti.start() }, 400)
        // Only dismissable once the button has appeared: an accidental early tap
        // must never rob her of the moment she just earned.
        root.postDelayed({ dismissible = true }, 1_750)
    }

    private fun isReducedMotion(): Boolean = Settings.Global.getFloat(
        requireContext().contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f
}
