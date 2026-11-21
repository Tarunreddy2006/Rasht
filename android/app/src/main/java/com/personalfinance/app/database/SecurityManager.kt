package com.personalfinance.app.database

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64

object SecurityManager {
    private const val PREFS_FILE = "rasht_secure_prefs"
    private const val KEY_DB_PASSPHRASE = "db_passphrase_key"
    private var cachedPassphrase: ByteArray? = null

    @Synchronized
    fun initialize(context: Context) {
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: Throwable) {
            // Native lib will be loaded by SQLiteOpenHelper static block
        }
    }

    @Synchronized
    fun getDatabasePassphrase(context: Context): ByteArray {
        cachedPassphrase?.let { return it }

        val appContext = context.applicationContext
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val sharedPreferences = EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            var storedKey = sharedPreferences.getString(KEY_DB_PASSPHRASE, null)
            if (storedKey == null) {
                // Generate a cryptographically secure 256-bit (32-byte) random passphrase
                val randomBytes = ByteArray(32)
                SecureRandom().nextBytes(randomBytes)
                storedKey = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    Base64.getEncoder().encodeToString(randomBytes)
                } else {
                    android.util.Base64.encodeToString(randomBytes, android.util.Base64.NO_WRAP)
                }
                sharedPreferences.edit().putString(KEY_DB_PASSPHRASE, storedKey).apply()
            }

            val decoded = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Base64.getDecoder().decode(storedKey)
            } else {
                android.util.Base64.decode(storedKey, android.util.Base64.NO_WRAP)
            }

            cachedPassphrase = decoded
            return decoded
        } catch (e: Exception) {
            val fallbackKey = "RashtPrivateLedgerSecuredPassphraseLocalOnly".toByteArray()
            cachedPassphrase = fallbackKey
            return fallbackKey
        }
    }
}
