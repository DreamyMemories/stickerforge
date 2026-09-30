package com.stickerforge.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stickerforge.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * API keys and preferences, backed by DataStore.
 *
 * A personal build can pre-seed keys through `local.properties`
 * (`GIPHY_API_KEY`, `KLIPY_API_KEY`); they are injected as BuildConfig fields
 * and are never committed. Anything typed into Settings wins over the
 * baked-in default, and the repo itself contains no keys.
 */
class ApiKeys(private val context: Context) {

    val giphyKey: Flow<String> = context.settingsDataStore.data.map { it[KEY_GIPHY].orEmpty() }
    val klipyKey: Flow<String> = context.settingsDataStore.data.map { it[KEY_KLIPY].orEmpty() }
    val publisher: Flow<String> = context.settingsDataStore.data.map { it[KEY_PUBLISHER].orEmpty() }

    suspend fun giphyKeyOnce(): String = giphyKey.first().ifBlank { BuildConfig.GIPHY_API_KEY }
    suspend fun klipyKeyOnce(): String = klipyKey.first().ifBlank { BuildConfig.KLIPY_API_KEY }
    suspend fun publisherOnce(): String = publisher.first()

    suspend fun setGiphyKey(value: String) {
        context.settingsDataStore.edit { it[KEY_GIPHY] = value.trim() }
    }

    suspend fun setKlipyKey(value: String) {
        context.settingsDataStore.edit { it[KEY_KLIPY] = value.trim() }
    }

    suspend fun setPublisher(value: String) {
        context.settingsDataStore.edit { it[KEY_PUBLISHER] = value.trim() }
    }

    private companion object {
        val KEY_GIPHY = stringPreferencesKey("giphy_api_key")
        val KEY_KLIPY = stringPreferencesKey("klipy_api_key")
        val KEY_PUBLISHER = stringPreferencesKey("publisher_name")
    }
}
