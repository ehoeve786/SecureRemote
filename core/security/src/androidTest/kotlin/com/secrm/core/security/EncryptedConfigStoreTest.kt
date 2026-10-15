package com.secrm.core.security

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs on a device or emulator: the store's keys live in the Android Keystore. */
@RunWith(AndroidJUnit4::class)
class EncryptedConfigStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsFile = File(context.filesDir.parentFile, "shared_prefs/secrm_secure_prefs.xml")

    @Before fun startFromEmpty() {
        context.deleteSharedPreferences("secrm_secure_prefs")
    }

    @Test fun valuesSurviveReopeningTheStore() {
        EncryptedConfigStore(context).mcpBearerToken = "tok-123"
        assertEquals("tok-123", EncryptedConfigStore(context).mcpBearerToken)
    }

    @Test fun valuesAndKeyNamesAreNotStoredInPlaintext() {
        EncryptedConfigStore(context).put("mcp.oauth", "SECRET-OAUTH-STATE")
        val onDisk = prefsFile.readText()
        assertTrue("prefs file should exist", onDisk.isNotEmpty())
        assertTrue("value leaked to disk", !onDisk.contains("SECRET-OAUTH-STATE"))
        assertTrue("key name leaked to disk", !onDisk.contains("mcp.oauth"))
    }

    @Test fun removeDeletesTheValue() {
        val store = EncryptedConfigStore(context)
        store.put("ssh.pass", "pw")
        store.remove("ssh.pass")
        assertNull(EncryptedConfigStore(context).get("ssh.pass"))
    }

    @Test fun clearingAFieldLeavesItEmpty() {
        val store = EncryptedConfigStore(context)
        store.sshPassword = "pw"
        store.sshPassword = null
        assertTrue(store.sshPassword.isNullOrEmpty())
    }
}
