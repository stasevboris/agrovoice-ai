package com.agronomy.phytomonitor.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class AppSettings(
    val model: String = "yolo26n",
    val crop: String = "all",
    val threshold: Float = 0.25f,
    val plot: String = "",
    val row: String = "",
    val location: Boolean = false,
    val lastPhoto: String? = null,
    val pendingCamera: String? = null,
    val continuous: Boolean = false,
    val freeSpeech: Boolean = false,
    val voiceEnabled: Boolean = true,
    val spokenFollowUp: Boolean = true,
    val server: String = ""
) {
    companion object {
        fun from(preferences: Preferences) = AppSettings(
            model = preferences[Keys.model] ?: "yolo26n",
            crop = (preferences[Keys.crop] ?: "all").lowercase(),
            threshold = (preferences[Keys.threshold] ?: 0.25f).coerceIn(0.1f, 0.8f),
            plot = preferences[Keys.plot].orEmpty(),
            row = preferences[Keys.row].orEmpty(),
            location = preferences[Keys.location] ?: false,
            lastPhoto = preferences[Keys.lastPhoto],
            pendingCamera = preferences[Keys.pendingCamera],
            continuous = preferences[Keys.continuous] ?: false,
            freeSpeech = preferences[Keys.freeSpeech] ?: false,
            voiceEnabled = preferences[Keys.voiceEnabled] ?: true,
            spokenFollowUp = preferences[Keys.spokenFollowUp] ?: true,
            server = preferences[Keys.server].orEmpty()
        )
    }
}

object Keys {
    val model = stringPreferencesKey("model")
    val crop = stringPreferencesKey("crop")
    val threshold = floatPreferencesKey("threshold")
    val plot = stringPreferencesKey("plot")
    val row = stringPreferencesKey("row")
    val location = booleanPreferencesKey("location")
    val lastPhoto = stringPreferencesKey("last_photo")
    val pendingCamera = stringPreferencesKey("pending_camera")
    val continuous = booleanPreferencesKey("continuous")
    val freeSpeech = booleanPreferencesKey("free_speech")
    val voiceEnabled = booleanPreferencesKey("voice_enabled")
    val spokenFollowUp = booleanPreferencesKey("spoken_follow_up")
    val server = stringPreferencesKey("server")
    val migrated = booleanPreferencesKey("legacy_imported")
}

/** A single DataStore per process; legacy preferences are retained as an untouched fallback copy. */
class SettingsStore private constructor(private val data: DataStore<Preferences>, private val scope: CoroutineScope) {
    val error = MutableStateFlow<String?>(null)
    val state = data.data.map<Preferences, AppSettings?> { AppSettings.from(it) }
        .catch { error.value = "Не удалось прочитать настройки: ${it.message}" }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun <T> set(key: Preferences.Key<T>, value: T?) = change {
        if (value == null) it.remove(key) else it[key] = value
    }

    fun <T> replaceIfMatches(key: Preferences.Key<T>, expected: T, value: T) = change {
        if (it[key] == expected) it[key] = value
    }

    // Enqueue writes in caller order before returning; disk I/O still runs in DataStore's scope.
    private fun change(edit: (MutablePreferences) -> Unit) = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            data.edit { edit(it) }
            error.value = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error.value = "Не удалось сохранить настройки: ${failure.message}"
        }
    }

    companion object {
        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext, CoroutineScope(SupervisorJob() + Dispatchers.IO))
                .also { instance = it }
        }

        internal fun create(context: Context, scope: CoroutineScope, file: File = context.preferencesDataStoreFile("agrovoice")): SettingsStore {
            val migration = LegacySettingsMigration(
                context.getSharedPreferences("field_demo", Context.MODE_PRIVATE),
                context.getSharedPreferences("voice_controls", Context.MODE_PRIVATE)
            )
            return SettingsStore(PreferenceDataStoreFactory.create(migrations = listOf(migration), scope = scope) { file }, scope)
        }
    }
}

internal class LegacySettingsMigration(
    private val field: SharedPreferences,
    private val voice: SharedPreferences
) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = currentData[Keys.migrated] != true

    override suspend fun migrate(currentData: Preferences): Preferences {
        val target = currentData.toMutablePreferences()
        val existing = currentData.asMap().keys.map { it.name }.toSet()
        for (key in listOf(Keys.model, Keys.crop, Keys.plot, Keys.row, Keys.lastPhoto, Keys.pendingCamera)) {
            if (key.name !in existing) field.getString(key.name, null)?.let { target[key] = it }
        }
        if (Keys.threshold.name !in existing && field.contains(Keys.threshold.name)) {
            target[Keys.threshold] = field.getFloat(Keys.threshold.name, 0.25f)
        }
        if (Keys.location.name !in existing && field.contains(Keys.location.name)) {
            target[Keys.location] = field.getBoolean(Keys.location.name, false)
        }
        for (key in listOf(Keys.continuous, Keys.freeSpeech)) {
            if (key.name !in existing && voice.contains(key.name)) target[key] = voice.getBoolean(key.name, false)
        }
        target[Keys.migrated] = true
        return target
    }

    override suspend fun cleanUp() = Unit
}
