package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule

const val PREFERENCES_NAME: String = "rebuild_preference"

/**
 * CloudStream DataStore ABI backed by Zuvio's app-private preferences.
 * The storage namespace is retained so existing Zuvio plugin settings are not lost.
 */
object DataStore {
    val mapper: JsonMapper = JsonMapper.builder()
        .addModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .build()

    fun getFolderName(folder: String, path: String): String =
        folder.trimEnd('/') + "/" + path.trimStart('/')

    fun Context.getSharedPrefs(): SharedPreferences =
        getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun Context.getDefaultSharedPrefs(): SharedPreferences = getSharedPrefs()

    fun Context.getKeys(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        return getSharedPrefs().all.keys.filter { it.startsWith(prefix) }
    }

    fun Context.containsKey(path: String): Boolean = getSharedPrefs().contains(path)

    fun Context.containsKey(folder: String, path: String): Boolean =
        containsKey(getFolderName(folder, path))

    fun Context.removeKey(path: String) {
        runCatching { getSharedPrefs().edit().remove(path).apply() }
    }

    fun Context.removeKey(folder: String, path: String) =
        removeKey(getFolderName(folder, path))

    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys(folder)
        return runCatching {
            getSharedPrefs().edit().apply {
                keys.forEach { key -> remove(key) }
            }.apply()
            keys.size
        }.getOrDefault(0)
    }

    fun <T> Context.setKey(path: String, value: T) {
        runCatching {
            getSharedPrefs().edit()
                .putString(path, mapper.writeValueAsString(value))
                .apply()
        }
    }

    fun <T> Context.setKey(folder: String, path: String, value: T) =
        setKey(getFolderName(folder, path), value)

    inline fun <reified T : Any> String.toKotlinObject(): T =
        mapper.readValue(this, T::class.java)

    fun <T> String.toKotlinObject(valueType: Class<T>): T =
        mapper.readValue(this, valueType)

    fun <T> Context.getKey(path: String, valueType: Class<T>): T? =
        runCatching {
            val json = getSharedPrefs().getString(path, null) ?: return null
            json.toKotlinObject(valueType)
        }.getOrNull()

    inline fun <reified T : Any> Context.getKey(path: String, defVal: T?): T? {
        val json = getSharedPrefs().getString(path, null) ?: return defVal
        return runCatching { json.toKotlinObject<T>() }.getOrNull()
    }

    inline fun <reified T : Any> Context.getKey(path: String): T? =
        getKey(path, null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String): T? =
        getKey(getFolderName(folder, path), null)

    inline fun <reified T : Any> Context.getKey(folder: String, path: String, defVal: T?): T? =
        getKey(getFolderName(folder, path), defVal) ?: defVal
}
