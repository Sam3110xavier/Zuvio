package com.lagradost.cloudstream3

import android.app.Activity

/** Compatibility facade for CloudStream extensions; navigation remains owned by Zuvio. */
object CommonActivity {
    @JvmField
    var activity: Activity? = null

    fun getActivity(): Activity? = activity

    fun setActivityInstance(newActivity: Activity?) {
        activity = newActivity
    }

    @Suppress("UNUSED_PARAMETER")
    fun showToast(message: String, duration: Int? = null) {}

    @Suppress("UNUSED_PARAMETER")
    fun showToast(activity: Activity?, message: String, duration: Int? = null) {}
}
