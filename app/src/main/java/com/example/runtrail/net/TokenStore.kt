package com.example.runtrail.net

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Signed-in account and its tokens. */
data class Session(
    val userId: String,
    val email: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
)

/** Session storage; an interface so tests can use [MemorySessionStore]. */
interface SessionStore {
    fun get(): Session?
    fun save(session: Session)
    fun clear()
}

class MemorySessionStore(private var session: Session? = null) : SessionStore {
    @Synchronized override fun get() = session
    @Synchronized override fun save(session: Session) { this.session = session }
    @Synchronized override fun clear() { session = null }
}

/**
 * Stores the session encrypted with an AES-GCM key from the Android Keystore.
 * Data that cannot be decrypted (e.g. restored from another device) counts as signed out.
 */
class TokenStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Volatile private var cache: Session? = null
    @Volatile private var loaded = false

    @Synchronized
    override fun get(): Session? {
        if (loaded) return cache
        loaded = true
        cache = try {
            val user = prefs.getString(K_USER, null) ?: return null
            Session(
                userId = user,
                email = prefs.getString(K_EMAIL, "") ?: "",
                displayName = prefs.getString(K_NAME, "") ?: "",
                accessToken = decrypt(prefs.getString(K_ACCESS, null) ?: return null),
                refreshToken = decrypt(prefs.getString(K_REFRESH, null) ?: return null),
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot read saved session, signing out", e)
            prefs.edit { clear() }
            null
        }
        return cache
    }

    @Synchronized
    override fun save(session: Session) {
        prefs.edit {
            putString(K_USER, session.userId)
            putString(K_EMAIL, session.email)
            putString(K_NAME, session.displayName)
            putString(K_ACCESS, encrypt(session.accessToken))
            putString(K_REFRESH, encrypt(session.refreshToken))
        }
        cache = session
        loaded = true
    }

    @Synchronized
    override fun clear() {
        prefs.edit { clear() }
        cache = null
        loaded = true
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(c.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        return "$iv:$data"
    }

    private fun decrypt(enc: String): String {
        val (iv, data) = enc.split(':', limit = 2)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(data, Base64.NO_WRAP)))
    }

    companion object {
        private const val TAG = "TokenStore"
        const val FILE = "runtrail_session"   // excluded from backups (res/xml/backup_rules.xml)
        private const val ALIAS = "runtrail_session_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val K_USER = "user_id"
        private const val K_EMAIL = "email"
        private const val K_NAME = "display_name"
        private const val K_ACCESS = "access"
        private const val K_REFRESH = "refresh"
    }
}
