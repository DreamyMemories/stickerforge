package com.stickerforge.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * API keys and preferences, backed by DataStore. Keys are entered by the user
 * and never bundled with the app.
 */
class ApiKeys(private val context: Context) {

    val giphyKey: Flow<String> = context.settingsDataStore.data.map { it[KEY_GIPHY].orEmpty() }
    val tenorKey: Flow<String> = context.settingsDataStore.data.map { it[KEY_TENOR].orEmpty() }
    val publisher: Flow<String> = context.settingsDataStore.data.map { it[KEY_PUBLISHER].orEmpty() }

    suspend fun giphyKeyOnce(): String = giphyKey.first()
    suspend fun tenorKeyOnce(): String = tenorKey.first()
    suspend fun publisherOnce(): String = publisher.first()

    suspend fun setGiphyKey(value: String) {
        context.settingsDataStore.edit { it[KEY_GIPHY] = value.trim() }
    }

    suspend fun setTenorKey(value: String) {
        context.settingsDataStore.edit { it[KEY_TENOR] = value.trim() }
    }

    suspend fun setPublisher(value: String) {
        context.settingsDataStore.edit { it[KEY_PUBLISHER] = value.trim() }
    }

    private companion object {
        val KEY_GIPHY = stringPreferencesKey("giphy_api_key")
        val KEY_TENOR = stringPreferencesKey("tenor_api_key")
        val KEY_PUBLISHER = stringPreferencesKey("publisher_name")
    }
}
