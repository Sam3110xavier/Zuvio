package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder

/** Android host ABI used by standard CloudStream .cs3 packages. */
abstract class Plugin : BasePlugin() {
    /**
     * CloudStream extensions may override load(Activity?) rather than load(Context)
     * or the BasePlugin no-argument load(). Keep all three entry points interoperable.
     */
    @Throws(Throwable::class)
    open fun load(activity: Activity?) {
        load()
    }

    @Throws(Throwable::class)
    open fun load(context: Context) {
        load(context as? Activity ?: AcraApplication.getActivity())
    }

    fun registerVideoClickAction(element: VideoClickAction) {
        element.sourcePlugin = filename
        VideoClickActionHolder.allVideoClickActions += element
    }

    var resources: Resources? = null
    var openSettings: ((Context) -> Unit)? = null
}
