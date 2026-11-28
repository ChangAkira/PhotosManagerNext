package com.example.photosmanager.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "photos_manager_prefs")

class PreferencesRepository(private val context: Context) {
    companion object {
        private val KEY_PHOTOS_PER_SESSION = intPreferencesKey("photos_per_session")
        private val KEY_NEARBY_PHOTOS_COUNT = intPreferencesKey("nearby_photos_count")
        private val KEY_ENABLED_ALBUMS = stringSetPreferencesKey("enabled_albums")
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        private val KEY_SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        private val KEY_ACTIVE_SESSION = stringPreferencesKey("active_session")
        private val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
        private val KEY_DND_ENABLED = booleanPreferencesKey("dnd_enabled")
        private val KEY_DND_START = intPreferencesKey("dnd_start_minute")
        private val KEY_DND_END = intPreferencesKey("dnd_end_minute")
        private val KEY_FLAGGED_ALBUMS = stringSetPreferencesKey("flagged_albums")
        private val KEY_TOTAL_DELETED = intPreferencesKey("total_deleted_photos")
        private val KEY_RECENT_MOVE_TARGETS = stringPreferencesKey("recent_move_targets")

        const val DEFAULT_PHOTOS_PER_SESSION = 5
        const val DEFAULT_NEARBY_PHOTOS_COUNT = 10
        const val DEFAULT_DND_START = 22 * 60
        const val DEFAULT_DND_END = 7 * 60
        const val DEFAULT_THEME_PALETTE = "beige"
        const val DELETED_MILESTONE_STEP = 100
        const val MAX_RECENT_MOVE_TARGETS = 5
        private const val RECENT_MOVE_DELIMITER = "\n"

        fun deletedMilestone(before: Int, after: Int): Int? {
            if (after <= before) return null
            val reached = after / DELETED_MILESTONE_STEP
            return if (reached > before / DELETED_MILESTONE_STEP && reached > 0)
                reached * DELETED_MILESTONE_STEP else null
        }

        fun updateRecentTargets(
            current: List<String>,
            targetRelativePath: String,
            maxCount: Int = MAX_RECENT_MOVE_TARGETS
        ): List<String> {
            val trimmed = targetRelativePath.trim()
            if (trimmed.isEmpty()) return current.take(maxCount)
            val updated = mutableListOf(trimmed)
            for (item in current) {
                val clean = item.trim()
                if (clean.isNotEmpty() && !clean.equals(trimmed, ignoreCase = true)) {
                    updated.add(clean)
                }
                if (updated.size >= maxCount) break
            }
            return updated
        }
    }

    val photosPerSession: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_PHOTOS_PER_SESSION] ?: DEFAULT_PHOTOS_PER_SESSION
    }

    val nearbyPhotosCount: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_NEARBY_PHOTOS_COUNT] ?: DEFAULT_NEARBY_PHOTOS_COUNT
    }

    val enabledAlbums: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_ENABLED_ALBUMS] ?: emptySet()
    }

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_FIRST_LAUNCH] ?: true
    }

    val isServiceEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_SERVICE_ENABLED] ?: false
    }

    val activeSessionJson: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[KEY_ACTIVE_SESSION]
    }

    val themePaletteId: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_THEME_PALETTE] ?: DEFAULT_THEME_PALETTE
    }

    val dndEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_DND_ENABLED] ?: false
    }

    val dndStartMinute: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_DND_START] ?: DEFAULT_DND_START
    }

    val dndEndMinute: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_DND_END] ?: DEFAULT_DND_END
    }

    val flaggedAlbums: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[KEY_FLAGGED_ALBUMS] ?: emptySet()
    }

    val totalDeletedPhotos: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[KEY_TOTAL_DELETED] ?: 0
    }

    val recentMoveTargets: Flow<List<String>> = context.dataStore.data.map { preferences ->
        val raw = preferences[KEY_RECENT_MOVE_TARGETS].orEmpty()
        if (raw.isBlank()) emptyList()
        else raw.split(RECENT_MOVE_DELIMITER).map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun recordRecentMoveTarget(relativePath: String) {
        if (relativePath.isBlank()) return
        context.dataStore.edit { preferences ->
            val current = preferences[KEY_RECENT_MOVE_TARGETS].orEmpty()
                .split(RECENT_MOVE_DELIMITER)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val updated = updateRecentTargets(current, relativePath)
            preferences[KEY_RECENT_MOVE_TARGETS] = updated.joinToString(RECENT_MOVE_DELIMITER)
        }
    }

    suspend fun setFlaggedAlbums(albums: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FLAGGED_ALBUMS] = albums
        }
    }

    suspend fun addDeletedPhotos(count: Int): Int {
        if (count <= 0) return totalDeletedPhotos.first()
        var after = count
        context.dataStore.edit { preferences ->
            after = (preferences[KEY_TOTAL_DELETED] ?: 0) + count
            preferences[KEY_TOTAL_DELETED] = after
        }
        return after
    }

    suspend fun setPhotosPerSession(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_PHOTOS_PER_SESSION] = count
        }
    }

    suspend fun setNearbyPhotosCount(count: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_NEARBY_PHOTOS_COUNT] = count
        }
    }

    suspend fun setEnabledAlbums(albums: Set<String>) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ENABLED_ALBUMS] = albums
        }
    }

    suspend fun setFirstLaunch(isFirst: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FIRST_LAUNCH] = isFirst
        }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SERVICE_ENABLED] = enabled
        }
    }

    suspend fun setActiveSession(json: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ACTIVE_SESSION] = json
        }
    }

    suspend fun clearActiveSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_ACTIVE_SESSION)
        }
    }

    suspend fun setThemePalette(id: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_THEME_PALETTE] = id
        }
    }

    suspend fun setDndEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DND_ENABLED] = enabled
        }
    }

    suspend fun setDndStartMinute(minute: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DND_START] = minute.coerceIn(0, 24 * 60 - 1)
        }
    }

    suspend fun setDndEndMinute(minute: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DND_END] = minute.coerceIn(0, 24 * 60 - 1)
        }
    }

    fun inDoNotDisturb(startMinute: Int, endMinute: Int, nowMinute: Int): Boolean =
        if (startMinute <= endMinute) nowMinute in startMinute until endMinute
        else nowMinute >= startMinute || nowMinute < endMinute
}
