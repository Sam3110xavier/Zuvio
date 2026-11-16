@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import com.lagradost.api.setContext
import java.lang.ref.WeakReference

/**
 * Compatibility facade for CloudStream extensions that expect the original
 * Application singleton. Zuvio remains the real Android Application.
 */
open class AcraApplication {
    companion object {
        @JvmStatic
        var context: Context? = null
            set(value) {
                field = value
                CloudStreamApp.context = value
                if (value != null) setContext(WeakReference(value))
            }

        private var activityReference: WeakReference<Activity>? = null

        @JvmStatic
        fun getActivity(): Activity? = activityReference?.get()

        @JvmStatic
        fun setActivity(activity: Activity?) {
            activityReference = activity?.let(::WeakReference)
            CommonActivity.setActivityInstance(activity)
            if (activity != null) setContext(WeakReference(activity))
        }

        @JvmStatic
        fun <T> getKey(path: String, key: String, default: T? = null): T? = default

        @JvmStatic
        fun <T> getKey(key: String, default: T? = null): T? = default

        @JvmStatic
        fun setKey(path: String, key: String, value: Any?) {}

        @JvmStatic
        fun setKey(key: String, value: Any?) {}

        @JvmStatic
        fun removeKeys(prefix: String) {}

        @JvmStatic
        fun removeKey(path: String, key: String) {}

        @JvmStatic
        fun removeKey(key: String) {}
    }
}
