package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference
import com.lagradost.cloudstream3.utils.DataStore

/** Host-context ABI used by a small subset of CloudStream extensions. */
class CloudStreamApp {
    companion object {
        private var contextReference: WeakReference<Context>? = null

        @JvmStatic
        var cfUserAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        @JvmStatic
        var context: Context?
            get() = contextReference?.get()
            set(value) {
                contextReference = value?.let(::WeakReference)
                if (value != null) com.lagradost.api.setContext(WeakReference(value as Any))
            }

        @JvmStatic
        tailrec fun Context.getActivity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.getActivity()
            else -> null
        }

        @JvmStatic
        fun <T : Any> getKey(path: String): T? = null

        @JvmStatic
        fun <T : Any> getKey(folder: String, path: String): T? = null

        @JvmStatic
        fun <T : Any> getKey(path: String, defVal: T?): T? {
            if (defVal == null) return null
            return defVal
        }

        @JvmStatic
        fun <T : Any> getKey(folder: String, path: String, defVal: T?): T? {
            if (defVal == null) return null
            return defVal
        }

        @JvmStatic
        fun <T : Any> getKeyClass(path: String, valueType: Class<T>): T? {
            val json = sharedPreferences()?.getString(path, null) ?: return null
            return runCatching { mapper.readValue(json, valueType) }.getOrNull()
        }

        @JvmStatic
        fun <T : Any> setKeyClass(path: String, value: T) = setKey(path, value)

        @JvmStatic
        fun <T> setKey(path: String, value: T) {
            val preferences = sharedPreferences() ?: return
            preferences.edit().apply {
                if (value == null) remove(path) else putString(path, mapper.writeValueAsString(value))
            }.apply()
        }

        @JvmStatic
        fun <T> setKey(folder: String, path: String, value: T) =
            setKey(DataStore.getFolderName(folder, path), value)

        @JvmStatic
        fun removeKey(path: String) {
            sharedPreferences()?.edit()?.remove(path)?.apply()
        }

        @JvmStatic
        fun removeKey(folder: String, path: String) = removeKey(DataStore.getFolderName(folder, path))

        @JvmStatic
        fun removeKeys(folder: String): Int? {
            val preferences = sharedPreferences() ?: return null
            val keys = getKeys(folder).orEmpty()
            preferences.edit().apply { keys.forEach(::remove) }.apply()
            return keys.size
        }

        @JvmStatic
        fun getKeys(folder: String): List<String>? {
            val preferences = sharedPreferences() ?: return null
            val prefix = folder.trimEnd('/') + "/"
            return preferences.all.keys.filter { it.startsWith(prefix) }
        }

        @JvmStatic
        fun openBrowser(url: String, fallbackWebView: Boolean = false) {}

        private fun sharedPreferences() = context?.let { currentContext ->
            with(DataStore) { currentContext.getSharedPrefs() }
        }
    }
}
