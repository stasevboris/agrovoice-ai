package com.agronomy.phytomonitor.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scopes = mutableListOf<CoroutineScope>()
    private val file = File(context.cacheDir, "settings-${UUID.randomUUID()}.preferences_pb")
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    @After
    fun close(): Unit = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        file.delete()
        Unit
    }

    @Test
    fun selectingAndImmediatelyRenamingUsesTheQueuedValue() = runBlocking {
        val store = SettingsStore.create(context, scope(), file)
        withTimeout(10000) { store.state.filterNotNull().first() }
        store.set(Keys.plot, "Выбранный")
        store.replaceIfMatches(Keys.plot, "Выбранный", "Переименованный").join()
        withTimeout(10000) { store.state.first { it?.plot == "Переименованный" } }
        store.replaceIfMatches(Keys.plot, "Чужой", "").join()
        assertEquals("Переименованный", store.state.value!!.plot)
    }

    @Test
    fun legacyValuesMoveOnceAndNewValuesSurviveReopening() = runBlocking {
        val old = context.getSharedPreferences("field_demo", 0)
        val voice = context.getSharedPreferences("voice_controls", 0)
        old.edit().clear().putString("model", "yolo26s").putString("crop", "Tomato")
            .putFloat("threshold", 0.35f).putString("plot", "Теплица 2").putString("row", "14")
            .putString("last_photo", "/photos/one.jpg").putString("pending_camera", "/photos/two.jpg")
            .putBoolean("location", true).putString("unrelated", "keep").commit()
        voice.edit().clear().putBoolean("continuous", true).putBoolean("free_speech", true).commit()
        val firstScope = scope()
        val store = SettingsStore.create(context, firstScope, file)
        val migrated = withTimeout(10000) { store.state.filterNotNull().first() }
        assertEquals("yolo26s", migrated.model)
        assertEquals("tomato", migrated.crop)
        assertEquals(0.35f, migrated.threshold)
        assertEquals("Теплица 2", migrated.plot)
        assertEquals("14", migrated.row)
        assertEquals("/photos/one.jpg", migrated.lastPhoto)
        assertEquals("/photos/two.jpg", migrated.pendingCamera)
        assertTrue(migrated.location && migrated.continuous && migrated.freeSpeech)
        assertEquals("keep", old.getString("unrelated", null))
        assertEquals("Tomato", old.getString("crop", null))

        store.set(Keys.model, "yolo26n").join()
        store.set(Keys.server, "https://example.invalid").join()
        store.set(Keys.spokenFollowUp, false).join()
        store.set(Keys.pendingCamera, null).join()
        firstScope.coroutineContext.job.cancelAndJoin()
        old.edit().putString("model", "outdated-value").commit()
        val reopened = SettingsStore.create(context, scope(), file)
        val saved = withTimeout(10000) { reopened.state.filterNotNull().first() }
        assertEquals("yolo26n", saved.model)
        assertEquals("https://example.invalid", saved.server)
        assertFalse(saved.spokenFollowUp)
        assertNull(saved.pendingCamera)
    }
}
