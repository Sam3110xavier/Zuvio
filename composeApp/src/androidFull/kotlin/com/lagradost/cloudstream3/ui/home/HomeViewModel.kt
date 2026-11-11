@file:Suppress("unused")

package com.lagradost.cloudstream3.ui.home

import androidx.lifecycle.ViewModel
import com.lagradost.cloudstream3.utils.DataStoreHelper

/** Compatibility shim: Nuvio stores watch progress outside CloudStream's legacy UI model. */
class HomeViewModel : ViewModel() {
    companion object {
        @JvmStatic
        suspend fun getResumeWatching(): List<DataStoreHelper.ResumeWatchingResult>? = emptyList()
    }
}
