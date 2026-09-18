package com.gadware.dcadm.review

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.gadware.dcadm.utils.DcadmLog
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.android.play.core.review.testing.FakeReviewManager
import java.util.concurrent.TimeUnit

/**
 * Manager providing simplified Google Play In-App Review integration.
 * Supports direct execution, milestone-based automated prompts, Play Store fallback,
 * and test mode via FakeReviewManager.
 */
object DcadmReviewManager {

    private const val TAG = "DcadmReview"
    private const val PREFS_NAME = "dcadm_in_app_review_prefs"
    private const val KEY_FIRST_LAUNCH = "first_launch_timestamp"
    private const val KEY_LAST_REVIEW_PROMPT = "last_review_prompt_timestamp"
    private const val KEY_ACTION_COUNT = "user_action_count"

    private var isTestMode: Boolean = false
    private var customReviewManager: ReviewManager? = null

    /**
     * Enables or disables test mode using Google Play's FakeReviewManager.
     * Useful for debugging on emulators or testing UI flows without Play Store quotas.
     */
    fun setTestMode(enabled: Boolean) {
        this.isTestMode = enabled
        DcadmLog.d(TAG, "In-App Review test mode set to: $enabled")
    }

    fun isTestMode(): Boolean = isTestMode

    /**
     * Allows injection of a custom ReviewManager (e.g. for unit testing).
     */
    fun setCustomReviewManager(manager: ReviewManager?) {
        this.customReviewManager = manager
    }

    private fun getReviewManager(context: Context): ReviewManager {
        customReviewManager?.let { return it }
        return if (isTestMode) {
            FakeReviewManager(context)
        } else {
            ReviewManagerFactory.create(context)
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Records a significant user milestone/action (e.g. database backup, data entry, task completed).
     * Used by [checkAndLaunchReview] to trigger reviews after meaningful user engagement.
     */
    fun recordAction(context: Context) {
        val prefs = getPrefs(context)
        val current = prefs.getInt(KEY_ACTION_COUNT, 0)
        prefs.edit().putInt(KEY_ACTION_COUNT, current + 1).apply()
        DcadmLog.d(TAG, "Recorded review milestone action. Total: ${current + 1}")
    }

    /**
     * Resets the recorded action count back to 0.
     */
    fun resetActionCount(context: Context) {
        getPrefs(context).edit().putInt(KEY_ACTION_COUNT, 0).apply()
    }

    /**
     * Gets the current number of recorded milestone actions.
     */
    fun getActionCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_ACTION_COUNT, 0)
    }

    /**
     * Directly launches the Google Play In-App Review flow.
     *
     * Note: Google Play determines whether the dialog is actually shown based on their
     * internal quota. The callback indicates flow completion, regardless of whether the
     * user reviewed, dismissed, or was quota-limited.
     *
     * @param activity Foreground activity to host the review dialog.
     * @param onComplete Optional callback invoked when the flow finishes (parameter is true if flow completed successfully, false if request failed).
     */
    fun launchReview(
        activity: Activity,
        onComplete: ((isSuccessful: Boolean) -> Unit)? = null
    ) {
        val manager = getReviewManager(activity)
        val request = manager.requestReviewFlow()

        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo: ReviewInfo = task.result
                val flow = manager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener { flowTask ->
                    // Record last prompt timestamp
                    val now = System.currentTimeMillis()
                    getPrefs(activity).edit().putLong(KEY_LAST_REVIEW_PROMPT, now).apply()

                    val isSuccess = flowTask.isSuccessful
                    DcadmLog.i(TAG, "In-App Review flow completed. isSuccessful: $isSuccess")
                    onComplete?.invoke(isSuccess)
                }
            } else {
                val exception = task.exception
                DcadmLog.e(TAG, "Failed to request In-App Review flow: ${exception?.message}", exception)
                onComplete?.invoke(false)
            }
        }
    }

    /**
     * Smart review trigger that checks if the user is eligible for an in-app review
     * based on time and engagement criteria.
     *
     * @param activity Foreground activity.
     * @param minDaysSinceInstall Minimum days since first app launch (default: 2 days).
     * @param minDaysBetweenPrompts Minimum days to wait before prompting again (default: 14 days).
     * @param minActions Minimum number of recorded actions via [recordAction] (default: 3 actions).
     * @param onComplete Callback invoked when review completes if eligible.
     * @return true if the review flow was initiated, false if user was not yet eligible.
     */
    fun checkAndLaunchReview(
        activity: Activity,
        minDaysSinceInstall: Int = 2,
        minDaysBetweenPrompts: Int = 14,
        minActions: Int = 3,
        onComplete: ((isSuccessful: Boolean) -> Unit)? = null
    ): Boolean {
        val prefs = getPrefs(activity)
        val now = System.currentTimeMillis()

        // 1. Initialize first launch time if not recorded
        var firstLaunch = prefs.getLong(KEY_FIRST_LAUNCH, 0L)
        if (firstLaunch == 0L) {
            firstLaunch = now
            prefs.edit().putLong(KEY_FIRST_LAUNCH, firstLaunch).apply()
        }

        // 2. Check days since installation
        val daysSinceInstall = TimeUnit.MILLISECONDS.toDays(now - firstLaunch)
        if (daysSinceInstall < minDaysSinceInstall) {
            DcadmLog.d(TAG, "In-App Review skipped: Installed $daysSinceInstall days ago (< $minDaysSinceInstall min).")
            return false
        }

        // 3. Check days since last prompt
        val lastPrompt = prefs.getLong(KEY_LAST_REVIEW_PROMPT, 0L)
        if (lastPrompt != 0L) {
            val daysSincePrompt = TimeUnit.MILLISECONDS.toDays(now - lastPrompt)
            if (daysSincePrompt < minDaysBetweenPrompts) {
                DcadmLog.d(TAG, "In-App Review skipped: Last prompted $daysSincePrompt days ago (< $minDaysBetweenPrompts min).")
                return false
            }
        }

        // 4. Check action engagement
        val actionCount = prefs.getInt(KEY_ACTION_COUNT, 0)
        if (actionCount < minActions) {
            DcadmLog.d(TAG, "In-App Review skipped: Actions $actionCount (< $minActions min).")
            return false
        }

        // Eligible! Reset action counter and trigger review
        resetActionCount(activity)
        launchReview(activity, onComplete)
        return true
    }

    /**
     * Opens the Google Play Store listing directly.
     * Recommended fallback when the user explicitly taps "Rate App" or "Write a Review"
     * in settings or profile screens.
     */
    fun openPlayStore(context: Context, customPackageName: String? = null) {
        val targetPackage = customPackageName ?: context.packageName
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$targetPackage")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
            if (context !is Activity) {
                marketIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
        } catch (e: ActivityNotFoundException) {
            // Play Store app not found, open in web browser
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$targetPackage"))
            if (context !is Activity) {
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}
