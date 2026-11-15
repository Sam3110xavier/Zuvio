@file:Suppress("unused")

package com.lagradost.cloudstream3.ui.home

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper

/** Compatibility stub; Zuvio's home screen and resume state are implemented separately. */
class HomeViewModel : ViewModel() {
    companion object {
        @JvmStatic
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? = emptyList()
    }
}
