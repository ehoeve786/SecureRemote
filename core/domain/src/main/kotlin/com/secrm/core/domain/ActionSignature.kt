package com.secrm.core.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 over a list of fields, so a value handed back through an exported entry point can be
 * checked against a key only this app holds. Each field is length-prefixed, so ("ab", "c") and
 * ("a", "bc") never produce the same input to the MAC.
 */
object ActionSignature {
    fun sign(key: ByteArray, vararg fields: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            mac.update(bytes)
        }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    /** True only when [signature] was produced by [sign] over exactly these [fields] with [key]. */
    fun verify(key: ByteArray, signature: String, vararg fields: String): Boolean =
        MessageDigest.isEqual(sign(key, *fields).toByteArray(), signature.toByteArray())
}
