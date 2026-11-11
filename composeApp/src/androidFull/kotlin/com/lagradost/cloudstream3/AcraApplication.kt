@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import com.lagradost.api.setContext
import java.lang.ref.WeakReference

/** Binary-compatibility shim for older CloudStream extensions. */
open class AcraApplication {
    companion object {
        @JvmStatic
        var context: Context? = null
            set(value) {
                field = value
                CloudStreamApp.context = value
                if (value != null) setContext(WeakReference(value as Any))
            }

        private var activityReference: WeakReference<Activity>? = null

        @JvmStatic
        fun getActivity(): Activity? = activityReference?.get()

        @JvmStatic
        fun setActivity(activity: Activity?) {
            activityReference = activity?.let(::WeakReference)
            if (activity != null) setContext(WeakReference(activity as Any))
        }

        // Older plugins use these static preference helpers. Keep the ABI safe;
        // the CloudStream settings UI owns its own persistence in this host.
        @JvmStatic
        fun <T> getKey(path: String, default: T? = null): T? = default

        @JvmStatic
        fun <T> getKey(path: String): T? = null

        @JvmStatic
        fun <T> getKey(folder: String, path: String): T? = null

        @JvmStatic
        fun <T> getKey(folder: String, path: String, default: T? = null): T? = default

        @JvmStatic
        fun <T> setKey(path: String, value: T) {}

        @JvmStatic
        fun <T> setKey(folder: String, path: String, value: T) {}

        @JvmStatic
        fun removeKeys(folder: String): Int? = 0

        @JvmStatic
        fun removeKey(path: String) {}

        @JvmStatic
        fun removeKey(folder: String, path: String) {}
    }
}
