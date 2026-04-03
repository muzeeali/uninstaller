package com.zeetech.uninstaller.bulk.apk.extractor.cleaner

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory

class RatingManager(private val context: Context) {

    private val TAG = "RatingManager"
    private val PREFS_NAME = "rating_prefs"
    private val KEY_HAS_RATED = "has_rated_locally"
    private val KEY_LAST_ASKED = "last_asked_timestamp"
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val manager: ReviewManager = ReviewManagerFactory.create(context)

    /**
     * Checks if the user is eligible for a rating prompt.
     * Eligibility: Not already rated AND (it's a new day or forced for testing)
     */
    fun canShowPrompt(): Boolean {
        if (prefs.getBoolean(KEY_HAS_RATED, false)) {
            Log.d(TAG, "Eligibility check: Failed. User already engaged with rating.")
            return false
        }

        val lastAsked = prefs.getLong(KEY_LAST_ASKED, 0L)
        val currentTime = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000L

        // Eligibility: If more than 24 hours passed since last ask
        val result = currentTime - lastAsked >= oneDayInMillis
        Log.d(TAG, "Eligibility check: ${if (result) "Passed" else "Failed (Too soon)"}")
        return result
    }

    /**
     * Marks the user as 'Asked' for today.
     */
    fun markAsAsked() {
        prefs.edit().putLong(KEY_LAST_ASKED, System.currentTimeMillis()).apply()
    }

    /**
     * Marks the user as 'Rated' permanently to stop automated prompts.
     */
    fun markAsRated() {
        prefs.edit().putBoolean(KEY_HAS_RATED, true).apply()
    }

    /**
     * Resets the rating status for debugging/testing.
     */
    fun resetRatingStatus() {
        prefs.edit().clear().apply()
        Log.d(TAG, "Rating status reset for testing.")
    }

    /**
     * Launches the rating flow.
     * @param forceStore If true, skips the native In-App Review attempt and goes straight to the Play Store.
     *                   Use this for manual clicks in Settings.
     */
    fun launchStore(activity: Activity, forceStore: Boolean = false) {
        markAsRated() // Once they click 'Rate Now', we stop asking forever locally.
        
        if (forceStore) {
            Log.d(TAG, "Manual request - Directing straight to Store.")
            redirectToStore(activity)
            return
        }

        Log.d(TAG, "Attempting Native In-App Review Flow...")
        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // We got the ReviewInfo, launch the native flow.
                val reviewInfo = task.result
                val flow = manager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener { _ ->
                    // Flow finished (either rated or dismissed). No action needed.
                    Log.d(TAG, "Native In-App Review Flow completed.")
                }
            } else {
                // Native flow failed (e.g. quota reached), fallback to store redirection.
                Log.d(TAG, "Native flow unavailable. Falling back to Store redirection.")
                redirectToStore(activity)
            }
        }
    }

    /**
     * Fallback logic to open the Play Store app or website.
     */
    private fun redirectToStore(activity: Activity) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            try {
                val anyStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(anyStoreIntent)
            } catch (e2: Exception) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(webIntent)
            }
        }
    }
}
