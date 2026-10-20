package com.agronomy.phytomonitor.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Passwords are never persisted. The entire session is authenticated/encrypted by Android Keystore. */
data class SyncSession(val origin: String, val userId: String, val username: String, val token: String,
                       val generation: String = UUID.randomUUID().toString()) {
    val account: String get() = "$origin|$userId"
    override fun toString() = "SyncSession(redacted)"
}

class SyncSessionStore private constructor(context: Context) {
    private val preferences = context.getSharedPreferences("sync_session", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(read())
    private val loginGate = SyncLoginGate()
    val session: StateFlow<SyncSession?> = mutable
    private val mutableAuthRequired = MutableStateFlow(preferences.getBoolean("auth_required", false))
    val authenticationRequired: StateFlow<Boolean> = mutableAuthRequired

    fun beginLogin(): Long = loginGate.begin()

    fun loginIsCurrent(attempt: Long): Boolean = loginGate.isCurrent(attempt)

    /** Called with the engine lock held; the callback only writes Room and must not acquire it again. */
    fun commitQueue(generation: String, active: () -> Boolean, write: () -> Unit) = loginGate.serialized {
        if (mutable.value?.generation != generation || !active()) throw CancellationException("Sync session ended")
        write()
    }

    fun completeLogin(attempt: Long, value: SyncSession, active: () -> Boolean, schedule: () -> Unit): Boolean {
        require(value.origin == SyncEndpoint.normalize(value.origin))
        val json = JSONObject().put("origin", value.origin).put("user", value.userId)
            .put("username", value.username).put("token", value.token).put("generation", value.generation)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        // Encryption can be slow. Check the attempt again after it, immediately before publication.
        return loginGate.commit(attempt, active) {
            check(preferences.edit().putString("iv", encode(cipher.iv)).putString("session", encode(encrypted)).remove("auth_required").commit()) {
                "Не удалось сохранить защищённый сеанс. Освободите место и повторите вход."
            }
            mutable.value = value
            mutableAuthRequired.value = false
            schedule()
        }
    }

    fun logout(stopWork: () -> Unit) {
        loginGate.invalidate {
            check(preferences.edit().clear().commit()) { "Не удалось завершить сеанс. Повторите выход." }
            mutable.value = null
            mutableAuthRequired.value = false
            stopWork()
        }
    }

    /** An expired worker must never clear a newer login or invalidate its pending attempt. */
    fun expire(generation: String, stopWork: () -> Unit): Boolean = loginGate.serialized {
        if (mutable.value?.generation != generation) return@serialized false
        check(preferences.edit().clear().putBoolean("auth_required", true).commit()) { "Не удалось сохранить остановку сеанса." }
        mutable.value = null
        mutableAuthRequired.value = true
        stopWork()
        true
    }

    private fun read(): SyncSession? = runCatching {
        val saved = preferences.getString("session", null) ?: return null
        val iv = requireNotNull(preferences.getString("iv", null))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, decode(iv)))
        cipher.updateAAD(AAD)
        val json = JSONObject(cipher.doFinal(decode(saved)).toString(Charsets.UTF_8))
        SyncSession(SyncEndpoint.normalize(json.getString("origin")), json.getString("user"),
            json.getString("username"), json.getString("token"), json.getString("generation"))
    }.getOrNull()

    @Synchronized private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        private const val ALIAS = "agrovoice_sync_v1"
        private val AAD = "agrovoice.sync.session.v1".toByteArray(Charsets.UTF_8)
        @Volatile private var instance: SyncSessionStore? = null
        fun get(context: Context): SyncSessionStore = instance ?: synchronized(this) {
            instance ?: SyncSessionStore(context.applicationContext).also { instance = it }
        }
        private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)
    }
}
