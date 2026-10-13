package com.secrm.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.secrm.core.security.EncryptedConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Minimal Bitwarden client: signs in with the personal API key, decrypts the
 * vault's SSH-key items in memory. The user key is kept wrapped by an
 * auth-bound Keystore key, so unlocking needs only fingerprint/device PIN.
 */
class BitwardenVault(private val config: EncryptedConfigStore) {
    data class SshKey(val id: String, val name: String, val privateKey: String)

    /** Decrypted SSH keys; null while locked. */
    @Volatile var keys: List<SshKey>? = null; private set
    val isConfigured get() = config.get(K_WRAPPED) != null

    fun privateKey(id: String) = keys?.firstOrNull { it.id == id }?.privateKey

    suspend fun login(server: String, clientId: String, secret: String, password: String, authorize: suspend () -> Boolean) {
        val (userKey, sync) = withContext(Dispatchers.IO) {
            val (token, sync) = fetch(server.trim().trimEnd('/'), clientId.trim(), secret.trim())
            val profile = sync.o("profile")!!
            val master = masterKey(password, profile.s("email")!!.trim().lowercase(), token)
            runCatching { decrypt(profile.s("key")!!, stretch(master)) }
                .getOrElse { error("Wrong master password") } to sync
        }
        check(authorize()) { "Authentication cancelled" }
        config.put(K_SERVER, server.trim().trimEnd('/'))
        config.put(K_ID, clientId.trim())
        config.put(K_SECRET, secret.trim())
        config.put(K_WRAPPED, wrap(userKey))
        keys = parse(sync, userKey)
    }

    /** Unlocks if needed (prompting via [authorize]); true when keys are available. */
    suspend fun ensureUnlocked(authorize: suspend () -> Boolean): Boolean {
        if (keys == null && isConfigured && authorize()) {
            val userKey = runCatching { unwrap(config.get(K_WRAPPED)!!) }
                .getOrElse { signOut(); error("Screen lock changed. Sign in to Bitwarden again.") }
            keys = withContext(Dispatchers.IO) {
                parse(fetch(config.get(K_SERVER)!!, config.get(K_ID)!!, config.get(K_SECRET)!!).second, userKey)
            }
        }
        return keys != null
    }

    fun lock() { keys = null }

    fun signOut() {
        lock()
        listOf(K_SERVER, K_ID, K_SECRET, K_WRAPPED).forEach(config::remove)
        keyStore().deleteEntry(ALIAS)
    }

    // ── Network ────────────────────────────────────────────────────────────

    private fun fetch(server: String, id: String, secret: String): Pair<JsonObject, JsonObject> {
        val cloud = Regex("https://vault\\.bitwarden\\.(com|eu)").matches(server)
        val identity = if (cloud) server.replace("vault.", "identity.") else "$server/identity"
        val api = if (cloud) server.replace("vault.", "api.") else "$server/api"
        val device = config.get(K_DEVICE) ?: UUID.randomUUID().toString().also { config.put(K_DEVICE, it) }
        val form = mapOf(
            "grant_type" to "client_credentials", "scope" to "api",
            "client_id" to id, "client_secret" to secret,
            "deviceType" to "0", "deviceIdentifier" to device, "deviceName" to "SecureRemote"
        ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val token = http("$identity/connect/token", form, null)
        return token to http("$api/sync?excludeDomains=true", null, token.s("access_token"))
    }

    private fun http(url: String, form: String?, bearer: String?): JsonObject {
        val c = URL(url).openConnection() as HttpURLConnection
        bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
        form?.let {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { o -> o.write(it.toByteArray()) }
        }
        val ok = c.responseCode in 200..299
        val body = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (!ok) error(runCatching { Json.parseToJsonElement(body).jsonObject.s("error_description") }.getOrNull()
            ?: "Bitwarden HTTP ${c.responseCode}")
        return Json.parseToJsonElement(body).jsonObject
    }

    // ── Crypto ─────────────────────────────────────────────────────────────

    private fun masterKey(password: String, email: String, t: JsonObject): ByteArray {
        val pw = password.toByteArray()
        val iterations = t.s("KdfIterations")!!.toInt()
        if (t.s("Kdf") == "1") return ByteArray(32).also {
            Argon2BytesGenerator().apply {
                init(Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                    .withSalt(MessageDigest.getInstance("SHA-256").digest(email.toByteArray()))
                    .withIterations(iterations)
                    .withMemoryAsKB(t.s("KdfMemory")!!.toInt() * 1024)
                    .withParallelism(t.s("KdfParallelism")!!.toInt())
                    .build())
            }.generateBytes(pw, it)
        }
        return PKCS5S2ParametersGenerator(SHA256Digest()).run {
            init(pw, email.toByteArray(), iterations)
            (generateDerivedParameters(256) as KeyParameter).key
        }
    }

    /** HKDF-Expand to a 64-byte enc‖mac key, as Bitwarden does for the master key. */
    private fun stretch(k: ByteArray) = hmac(k, "enc".toByteArray() + 1.toByte()) + hmac(k, "mac".toByteArray() + 1.toByte())

    private fun hmac(key: ByteArray, data: ByteArray) =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** Decrypts an AES-256-CBC + HMAC-SHA256 ("2.iv|ct|mac") EncString with a 64-byte key. */
    private fun decrypt(enc: String, key: ByteArray): ByteArray {
        require(enc.startsWith("2.")) { "Unsupported encryption type" }
        val (iv, ct, mac) = enc.substring(2).split('|').map { Base64.decode(it, Base64.DEFAULT) }
        require(MessageDigest.isEqual(hmac(key.copyOfRange(32, 64), iv + ct), mac)) { "Bad MAC" }
        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, 32, "AES"), IvParameterSpec(iv))
            doFinal(ct)
        }
    }

    /** Personal (non-org), non-deleted SSH-key items (cipher type 5). */
    private fun parse(sync: JsonObject, userKey: ByteArray) = (sync.f("ciphers") as JsonArray).mapNotNull { e ->
        val c = e.jsonObject
        if (c.s("type") != "5" || c.s("organizationId") != null || c.s("deletedDate") != null) return@mapNotNull null
        runCatching {
            val k = c.s("key")?.let { decrypt(it, userKey) } ?: userKey
            SshKey(c.s("id")!!, String(decrypt(c.s("name")!!, k)),
                String(decrypt(c.o("sshKey")!!.s("privateKey")!!, k)))
        }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    // ── Keystore wrapping (auth-bound, 60 s after fingerprint/PIN) ────────

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun wrappingKey(): SecretKey = keyStore().getKey(ALIAS, null) as SecretKey?
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(60,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .build())
        }.generateKey()

    private fun wrap(data: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, wrappingKey())
        Base64.encodeToString(iv + doFinal(data), Base64.NO_WRAP)
    }

    private fun unwrap(s: String) = Base64.decode(s, Base64.NO_WRAP).let { b ->
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, b, 0, 12))
            doFinal(b, 12, b.size - 12)
        }
    }

    private companion object {
        const val ALIAS = "secrm_bitwarden"
        const val K_SERVER = "bw.server"
        const val K_ID = "bw.client_id"
        const val K_SECRET = "bw.client_secret"
        const val K_WRAPPED = "bw.user_key"
        const val K_DEVICE = "bw.device"

        // Bitwarden and Vaultwarden differ in key casing; match case-insensitively.
        fun JsonObject.f(k: String) = entries.firstOrNull { it.key.equals(k, true) }?.value
        fun JsonObject.s(k: String) = (f(k) as? JsonPrimitive)?.contentOrNull
        fun JsonObject.o(k: String) = f(k) as? JsonObject
    }
}

/** Fingerprint / device-PIN prompt; true on success. */
suspend fun FragmentActivity.authenticate(): Boolean = suspendCancellableCoroutine { c ->
    BiometricPrompt(this, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = c.resume(true)
        override fun onAuthenticationError(code: Int, msg: CharSequence) = c.resume(false)
    }).authenticate(BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Bitwarden SSH keys")
        .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        .build())
}
