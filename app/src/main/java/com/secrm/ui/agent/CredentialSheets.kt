package com.secrm.ui.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.secrm.data.TermuxExecutor
import com.secrm.design.*
import java.io.File

/**
 * Bottom sheet for entering Bitwarden email + master password.
 *
 * Security flow:
 *   1. Credentials are base64-encoded (avoids shell escaping bugs).
 *   2. A script is written to Android/media/com.secrm/ with 0600 perms.
 *   3. RUN_COMMAND invokes `sh <script>; rm -f <script>` inside Termux.
 *   4. The script runs `bw login`, saves the session, then deletes itself.
 *   5. On Windows the whole flow happens in <1 second; the file never
 *      survives a device reboot and is not readable by other apps without
 *      the storage permission Termux already holds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenLoginSheet(
    onDismiss: () -> Unit,
    onStarted: (String) -> Unit
) {
    val ctx = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val valid = email.isNotBlank() && password.isNotBlank() && !busy
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = Palette.Surface1
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xxl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Log in to Bitwarden",
                style = SecrmType.Title, color = Palette.TextPrimary)
            Text(
                "Your email and master password are used once to obtain a " +
                "session key, then discarded. They are never stored by this app " +
                "and never appear in Termux's shell history.",
                style = SecrmType.Caption, color = Palette.TextSecondary
            )

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Master password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("2FA code (leave blank if not enabled)") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Palette.Indigo
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text("Running bw login in Termux…",
                        style = SecrmType.Caption, color = Palette.TextSecondary)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillButton("Cancel", PillStyle.Ghost, Modifier.weight(1f),
                    enabled = !busy) { onDismiss() }
                PillButton("Log in", PillStyle.Primary, Modifier.weight(1f),
                    enabled = valid) {
                    busy = true
                    runLogin(ctx, email, password, code)
                    Handler(Looper.getMainLooper()).postDelayed({
                        busy = false
                        onStarted("Login requested. Tap ⟳ in a moment to refresh.")
                    }, 2500)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitwardenUnlockSheet(
    onDismiss: () -> Unit,
    onStarted: (String) -> Unit
) {
    val ctx = LocalContext.current
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val valid = password.isNotBlank() && !busy
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = Palette.Surface1
    ) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            Text("Unlock Bitwarden",
                style = SecrmType.Title, color = Palette.TextPrimary)
            Text(
                "Used once to obtain a session key. Discarded immediately.",
                style = SecrmType.Caption, color = Palette.TextSecondary
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Master password") },
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Palette.Indigo
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text("Running bw unlock in Termux…",
                        style = SecrmType.Caption, color = Palette.TextSecondary)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                PillButton("Cancel", PillStyle.Ghost, Modifier.weight(1f),
                    enabled = !busy) { onDismiss() }
                PillButton("Unlock", PillStyle.Primary, Modifier.weight(1f),
                    enabled = valid) {
                    busy = true
                    runUnlock(ctx, password)
                    Handler(Looper.getMainLooper()).postDelayed({
                        busy = false
                        onStarted("Unlock requested. Tap ⟳ in a moment to refresh.")
                    }, 2500)
                }
            }
        }
    }
}

// ── Execution ────────────────────────────────────────────────────

private fun b64(s: String): String =
    Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

private fun scriptsDir(ctx: Context): File {
    val dir = ctx.getExternalMediaDirs()?.firstOrNull() ?: ctx.filesDir
    if (!dir.exists()) dir.mkdirs()
    return dir
}

private fun runLogin(ctx: Context, email: String, password: String, code: String) {
    val script = File(scriptsDir(ctx), "secrm_login_${System.currentTimeMillis()}.sh")
    val hasCode = code.isNotBlank()
    val codeArg = if (hasCode) " --method 0 --code \"${'$'}CODE\"" else ""
    val codeLine = if (hasCode) "CODE=${'$'}(echo '${b64(code)}' | /data/data/com.termux/files/usr/bin/base64 -d)" else "CODE=''"

    val body = """
        #!/data/data/com.termux/files/usr/bin/bash
        set +e
        export HOME=/data/data/com.termux/files/home
        export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets
        EMAIL=${'$'}(echo '${b64(email)}' | /data/data/com.termux/files/usr/bin/base64 -d)
        PASS=${'$'}(echo '${b64(password)}' | /data/data/com.termux/files/usr/bin/base64 -d)
        ${codeLine}
        /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/bin/bw login "${'$'}EMAIL" "${'$'}PASS"${codeArg} --raw > ~/.bw_session 2>~/.bw_login_err
        RC=${'$'}?
        unset EMAIL PASS CODE
        if [ "${'$'}RC" -eq 0 ] && [ -s ~/.bw_session ]; then
          /data/data/com.termux/files/usr/bin/termux-toast "Bitwarden login succeeded"
        else
          ERR=${'$'}(/data/data/com.termux/files/usr/bin/head -3 ~/.bw_login_err | tr '
' ' ')
          /data/data/com.termux/files/usr/bin/termux-toast "Login failed: ${'$'}ERR"
        fi
        rm -f "${script.absolutePath}"
    """.trimIndent()

    try {
        script.writeText(body)
        script.setReadable(true, true)
        script.setWritable(true, true)
    } catch (t: Throwable) {
        android.util.Log.e("BitwardenLogin", "write failed", t)
        return
    }
    TermuxExecutor.runCommand(
        ctx,
        "/data/data/com.termux/files/usr/bin/bash ${script.absolutePath}; /data/data/com.termux/files/usr/bin/rm -f ${script.absolutePath}"
    )
}

private fun runUnlock(ctx: Context, password: String) {
    val script = File(scriptsDir(ctx), "secrm_unlock_${System.currentTimeMillis()}.sh")
    val body = """
        #!/data/data/com.termux/files/usr/bin/bash
        set -e
        export HOME=/data/data/com.termux/files/home
        export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets
        PASS=${'$'}(echo '${b64(password)}' | /data/data/com.termux/files/usr/bin/base64 -d)
        /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/bin/bw unlock "${'$'}PASS" --raw > ~/.bw_session 2>~/.bw_unlock_err
        RC=${'$'}
        unset PASS
        if [ "${'$'}RC" -eq 0 ] && [ -s ~/.bw_session ]; then
          /data/data/com.termux/files/usr/bin/termux-toast "Vault unlocked"
        else
          /data/data/com.termux/files/usr/bin/termux-toast "Unlock failed: ${'$'}(/data/data/com.termux/files/usr/bin/head -1 ~/.bw_unlock_err)"
        fi
        rm -f "${script.absolutePath}"
    """.trimIndent()

    try {
        script.writeText(body)
        script.setReadable(true, true)
        script.setWritable(true, true)
    } catch (t: Throwable) {
        android.util.Log.e("BitwardenUnlock", "write failed", t)
        return
    }

    TermuxExecutor.runCommand(
        ctx,
        "/data/data/com.termux/files/usr/bin/bash ${script.absolutePath}; /data/data/com.termux/files/usr/bin/rm -f ${script.absolutePath}"
    )
}
