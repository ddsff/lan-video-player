package com.lanvideo.player

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ConnectionHistory(context: Context) {
    class Record(val host: String, val share: String, val account: String, val password: String?) {
        fun matches(other: Record): Boolean =
            host.equals(other.host, ignoreCase = true) && share.equals(other.share, ignoreCase = true) &&
                account.equals(other.account, ignoreCase = true)

        fun withoutPassword() = Record(host, share, account, null)
    }

    private val storage = context.getSharedPreferences("connection_history", Context.MODE_PRIVATE)

    fun load(): List<Record> = synchronized(lock) {
        val ciphertext = storage.getString(RECORDS, null) ?: return@synchronized emptyList()
        val packed = Base64.decode(ciphertext, Base64.NO_WRAP)
        require(packed.size >= IV_BYTES + TAG_BITS / Byte.SIZE_BITS)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IOException("History key is unavailable"), GCMParameterSpec(TAG_BITS, packed, 0, IV_BYTES))
        val records = JSONArray(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES).decodeToString())
        List(minOf(records.length(), LIMIT)) { index ->
            val record = records.getJSONObject(index)
            Record(
                record.getString("host"), record.getString("share"),
                record.getString("account"),
                if (record.isNull("password")) null else record.getString("password")
            )
        }
    }

    fun remember(record: Record): List<Record> = synchronized(lock) {
        val existing = load()
        val updated = (listOf(record) + existing.filterNot { it.matches(record) }).take(LIMIT)
        write(updated)
        updated
    }

    fun forgetPassword(record: Record): List<Record> = synchronized(lock) {
        val existing = load()
        if (existing.none { it.matches(record) && it.password != null }) return@synchronized existing
        existing.map { if (it.matches(record)) it.withoutPassword() else it }.also(::write)
    }

    private fun write(updated: List<Record>) {
        val records = JSONArray()
        updated.forEach {
            records.put(JSONObject()
                .put("host", it.host)
                .put("share", it.share)
                .put("account", it.account)
                .put("password", it.password))
        }

        val store = keyStore()
        val key = store.getKey(KEY_ALIAS, null) as? SecretKey ?: createKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        check(cipher.iv.size == IV_BYTES)
        val packed = cipher.iv + cipher.doFinal(records.toString().encodeToByteArray())
        if (!storage.edit().putString(RECORDS, Base64.encodeToString(packed, Base64.NO_WRAP)).commit()) {
            throw IOException("Could not save connection history")
        }
    }

    fun clear() = synchronized(lock) {
        if (!storage.edit().remove(RECORDS).commit()) throw IOException("Could not clear connection history")
    }

    private fun keyStore() = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun createKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()

    companion object {
        private val lock = Any()
        private const val RECORDS = "encrypted_records"
        private const val KEY_ALIAS = "lanvideo.connection-history.v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val LIMIT = 10
    }
}
