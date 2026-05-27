package com.suzuai.luminaris.shared.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.luminarisStore by preferencesDataStore(name = "luminaris_backend")

/**
 * Persists the only two values BOTH APKs need locally: the backend URL and
 * the bearer token. Everything else (SSH, provider, sessions, logs) lives on
 * the backend and is fetched on demand.
 */
class BackendStore(private val context: Context) {

    private object Keys {
        val Url = stringPreferencesKey("backend_url")
        val Token = stringPreferencesKey("backend_token")
    }

    val url: Flow<String> = context.luminarisStore.data.map { it[Keys.Url].orEmpty() }
    val token: Flow<String> = context.luminarisStore.data.map { it[Keys.Token].orEmpty() }

    /** Convenience: emits a non-null pair so screens can react in one collect. */
    val pair: Flow<Pair<String, String>> = combine(url, token) { u, t -> u to t }

    suspend fun set(url: String, token: String) {
        context.luminarisStore.edit {
            it[Keys.Url] = url.trim().trimEnd('/')
            it[Keys.Token] = token.trim()
        }
    }

    suspend fun clear() {
        context.luminarisStore.edit { it.clear() }
    }
}
