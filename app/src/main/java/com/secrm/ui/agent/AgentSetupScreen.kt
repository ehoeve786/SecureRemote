package com.secrm.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.secrm.data.BitwardenStatus
import com.secrm.data.BitwardenStatusParser
import com.secrm.data.TermuxExecutor
import com.secrm.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Scripts (background, non-interactive) ─────────────────────

private fun scriptCheckBw(statusPath: String): String = """
export HOME=/data/data/com.termux/files/home
export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets
DBG=/storage/emulated/0/Android/media/com.secrm/bw_debug.log
{
  echo "when=${'$'}(date)"
  echo "HOME=${'$'}HOME"
  echo "PWD=${'$'}PWD"
  echo "which_bw=${'$'}(command -v /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/bin/bw 2>&1)"
  echo "bw_session_exists=${'$'}([ -f ${'$'}HOME/.bw_session ] && echo yes || echo no)"
  echo "bw_session_bytes=${'$'}(wc -c < ${'$'}HOME/.bw_session 2>/dev/null || echo 0)"
} > "${'$'}DBG" 2>&1

if ! command -v /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/bin/bw >/dev/null 2>&1; then
  echo '{"status":"missing"}' > "$statusPath"
  exit 0
fi

SESSION=${'$'}(cat ${'$'}HOME/.bw_session 2>/dev/null)
echo "session_len=${'$'}{#SESSION}" >> "${'$'}DBG"

if [ -n "${'$'}SESSION" ]; then
  /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/bin/bw status --session "${'$'}SESSION" \
    > "$statusPath" 2>>"${'$'}DBG"
fi
if [ ! -s "$statusPath" ]; then
  /data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/bin/bw status > "$statusPath" 2>>"${'$'}DBG"
fi
if [ ! -s "$statusPath" ]; then
  echo '{"status":"unauthenticated"}' > "$statusPath"
fi
""".trimIndent()

private val SCRIPT_INSTALL_BW = """
export HOME=/data/data/com.termux/files/home
export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets
/data/data/com.termux/files/usr/bin/pkg install -y nodejs-lts >/dev/null 2>&1
/data/data/com.termux/files/usr/bin/npm install -g @bitwarden/cli >/dev/null 2>&1
/data/data/com.termux/files/usr/bin/termux-toast "Bitwarden CLI installed"
""".trimIndent()

private fun scriptLoadKey() = """
export HOME=/data/data/com.termux/files/home
export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets
TOAST=/data/data/com.termux/files/usr/bin/termux-toast
NODE=/data/data/com.termux/files/usr/bin/node
BW=/data/data/com.termux/files/usr/bin/bw
JQ=/data/data/com.termux/files/usr/bin/jq
GREP=/data/data/com.termux/files/usr/bin/grep
HEAD=/data/data/com.termux/files/usr/bin/head
CUT=/data/data/com.termux/files/usr/bin/cut
SSHADD=/data/data/com.termux/files/usr/bin/ssh-add

KEY_NAME="${'$'}1"

if [ ! -s ${'$'}HOME/.bw_session ]; then
  ${'$'}TOAST "No session. Unlock first."
  exit 1
fi
if ! command -v ${'$'}JQ >/dev/null 2>&1; then
  ${'$'}TOAST "jq missing. Run: pkg install jq"
  exit 1
fi

SESSION=${'$'}(cat ${'$'}HOME/.bw_session)
JSON=${'$'}(${'$'}NODE ${'$'}BW get item "${'$'}KEY_NAME" --session "${'$'}SESSION" 2>&1)
RC=${'$'}?

if [ "${'$'}RC" -ne 0 ]; then
  if echo "${'$'}JSON" | ${'$'}GREP -q "More than one result"; then
    IDS=${'$'}(echo "${'$'}JSON" | ${'$'}GREP -oE "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    KEY_COUNT=0
    KEY_ID=""
    for id in ${'$'}IDS; do
      T=${'$'}(${'$'}NODE ${'$'}BW get item "${'$'}id" --session "${'$'}SESSION" 2>/dev/null | ${'$'}JQ -r .type)
      if [ "${'$'}T" = "5" ]; then
        KEY_COUNT=${'$'}((KEY_COUNT + 1))
        KEY_ID="${'$'}id"
      fi
    done
    if [ "${'$'}KEY_COUNT" -eq 0 ]; then
      ${'$'}TOAST "No SSH key matches ${'$'}KEY_NAME"
      exit 1
    fi
    if [ "${'$'}KEY_COUNT" -gt 1 ]; then
      ${'$'}TOAST "Multiple SSH keys match — paste an ID"
      exit 1
    fi
    JSON=${'$'}(${'$'}NODE ${'$'}BW get item "${'$'}KEY_ID" --session "${'$'}SESSION" 2>&1)
  else
    MSG=${'$'}(echo "${'$'}JSON" | ${'$'}HEAD -1 | ${'$'}CUT -c1-80)
    ${'$'}TOAST "bw: ${'$'}MSG"
    exit 1
  fi
fi

TYPE=${'$'}(echo "${'$'}JSON" | ${'$'}JQ -r .type)
if [ "${'$'}TYPE" != "5" ]; then
  ${'$'}TOAST "Item ${'$'}KEY_NAME is not an SSH key (type=${'$'}TYPE)"
  exit 1
fi

KEY=${'$'}(echo "${'$'}JSON" | ${'$'}JQ -r ".sshKey.privateKey // empty")
if [ -z "${'$'}KEY" ]; then
  ${'$'}TOAST "SSH key item has no private key stored"
  exit 1
fi

SOCK=/data/data/com.termux/files/usr/var/run/ssh-agent.socket
SVC_ENABLE=/data/data/com.termux/files/usr/bin/sv-enable
SVC_UP=/data/data/com.termux/files/usr/bin/sv
SSH_AGENT_BIN=/data/data/com.termux/files/usr/bin/ssh-agent
SLEEP=/data/data/com.termux/files/usr/bin/sleep
MKDIR=/data/data/com.termux/files/usr/bin/mkdir

${'$'}MKDIR -p /data/data/com.termux/files/usr/var/run 2>/dev/null || true

if [ ! -S "${'$'}SOCK" ]; then
  ${'$'}TOAST "Starting ssh-agent…"
  # Prefer the termux-services daemon if available.
  if [ -x "${'$'}SVC_ENABLE" ]; then
    "${'$'}SVC_ENABLE" ssh-agent 2>/dev/null || true
    "${'$'}SVC_UP" up ssh-agent 2>/dev/null || true
  fi
  # Wait up to 5s for the socket.
  for i in 1 2 3 4 5; do
    [ -S "${'$'}SOCK" ] && break
    ${'$'}SLEEP 1
  done
  # Fallback: launch ssh-agent directly bound to our known path.
  if [ ! -S "${'$'}SOCK" ]; then
    ${'$'}SSH_AGENT_BIN -a "${'$'}SOCK" >/dev/null 2>&1 || true
    ${'$'}SLEEP 1
  fi
fi

if [ ! -S "${'$'}SOCK" ]; then
  ${'$'}TOAST "Could not start ssh-agent. Run: pkg install openssh"
  exit 1
fi

export SSH_AUTH_SOCK="${'$'}SOCK"
echo "${'$'}KEY" | ${'$'}SSHADD - >/dev/null 2>&1 && ${'$'}TOAST "SSH key loaded to agent" || ${'$'}TOAST "ssh-add still failed"
""".trimIndent()

private val SCRIPT_BRIDGE = """
export HOME=/data/data/com.termux/files/home
export PATH=/data/data/com.termux/files/usr/bin:/data/data/com.termux/files/usr/bin/applets
AGENT_SOCK=${'$'}PREFIX/var/run/ssh-agent.socket
if [ ! -S "${'$'}AGENT_SOCK" ]; then
  /data/data/com.termux/files/usr/bin/termux-toast "ssh-agent not running. Run: sv-enable ssh-agent"
  exit 1
fi
/data/data/com.termux/files/usr/bin/pkill -f 'socat.*TCP-LISTEN:6000' 2>/dev/null || true
nohup /data/data/com.termux/files/usr/bin/socat TCP-LISTEN:6000,bind=127.0.0.1,reuseaddr,fork \
      UNIX-CONNECT:${'$'}AGENT_SOCK >/dev/null 2>&1 &
sleep 1
if /data/data/com.termux/files/usr/bin/nc -z 127.0.0.1 6000 2>/dev/null; then
  /data/data/com.termux/files/usr/bin/termux-toast "Agent bridge active on 127.0.0.1:6000"
else
  /data/data/com.termux/files/usr/bin/termux-toast "Bridge failed to start"
fi
""".trimIndent()

// ── Screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSetupScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var termuxInstalled by remember { mutableStateOf(TermuxExecutor.isInstalled(ctx)) }
    var hasPermission by remember { mutableStateOf(TermuxExecutor.hasPermission(ctx)) }
    var status by remember { mutableStateOf<BitwardenStatus?>(null) }
    var statusLoading by remember { mutableStateOf(false) }
    var keyName by remember { mutableStateOf("") }
    var lastMessage by remember { mutableStateOf<String?>(null) }
    var showLoginSheet by remember { mutableStateOf(false) }
    var showUnlockSheet by remember { mutableStateOf(false) }

    fun refreshStatus() {
        statusLoading = true
        scope.launch {
            try {
                val mediaDirs = ctx.getExternalMediaDirs()
                val ext = mediaDirs?.firstOrNull() ?: ctx.filesDir
                val statusFile = java.io.File(ext, "bw_status.json")
                runCatching { statusFile.delete() }

                withContext(Dispatchers.IO) {
                    TermuxExecutor.runCommand(ctx, scriptCheckBw(statusFile.absolutePath))
                }

                var resolved: BitwardenStatus? = null
                for (i in 0 until 12) {
                    kotlinx.coroutines.delay(500)
                    if (!statusFile.exists()) continue
                    val text = runCatching { statusFile.readText() }.getOrNull()
                    if (text.isNullOrBlank()) continue
                    val candidate = BitwardenStatusParser.parse(text)
                    if (candidate != null) { resolved = candidate; break }
                }

                status = resolved ?: BitwardenStatusParser.Missing
                if (resolved == null) {
                    lastMessage = "No status file received. Verify Termux has 'All files access'."
                }
            } catch (t: Throwable) {
                lastMessage = "Status check failed: ${t.message}"
                status = BitwardenStatusParser.Missing
            } finally {
                statusLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshStatus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bitwarden Agent", style = SecrmType.Title, color = Palette.TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ChevronLeft, "Back", tint = Palette.TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Palette.Void)
            )
        },
        containerColor = Palette.Void
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(
                start = Space.screenPadding, end = Space.screenPadding,
                top = Space.md, bottom = Space.xxl
            ),
            verticalArrangement = Arrangement.spacedBy(Space.md)
        ) {
            item { StatusCard(status, statusLoading, onRefresh = { refreshStatus() }) }
            item { PrereqRow(termuxInstalled, hasPermission) }

            item {
                StepCard(
                    n = 1, title = "Install Bitwarden CLI",
                    body = "Installs Node.js and @bitwarden/cli in Termux.",
                    buttonText = if (status?.status == "missing") "Install" else "Reinstall",
                    enabled = termuxInstalled && hasPermission,
                    onClick = {
                        TermuxExecutor.runCommand(ctx, SCRIPT_INSTALL_BW)
                        lastMessage = "Installation started — tap ⟳ in a minute"
                    }
                )
            }

            item {
                StepCard(
                    n = 2, title = "Log in to Bitwarden",
                    body = "Enter your credentials here — they're used once and never stored.",
                    buttonText = "Open login",
                    enabled = termuxInstalled && hasPermission &&
                            status?.status != "missing" &&
                            status?.isAuthenticated == false,
                    onClick = { showLoginSheet = true }
                )
            }

            item {
                StepCard(
                    n = 3, title = "Unlock the vault",
                    body = "Enter your master password to obtain a session key.",
                    buttonText = "Open unlock",
                    enabled = termuxInstalled && hasPermission && status?.status == "locked",
                    onClick = { showUnlockSheet = true }
                )
            }

            item {
                Column {
                    StepCard(
                        n = 4, title = "Load SSH key to agent",
                        body = "Fetches the named item from your vault and adds it to Termux's ssh-agent.",
                        buttonText = "Load key",
                        enabled = termuxInstalled && hasPermission &&
                                status?.isUnlocked == true && keyName.isNotBlank(),
                        onClick = {
                            TermuxExecutor.runCommand(ctx, scriptLoadKey(), listOf(keyName))
                            lastMessage = "Key load requested for '$keyName'"
                        }
                    )
                    Spacer(Modifier.height(Space.sm))
                    OutlinedTextField(
                        value = keyName, onValueChange = { keyName = it },
                        label = { Text("Bitwarden item name") },
                        placeholder = { Text("work-ed25519") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                StepCard(
                    n = 5, title = "Start TCP bridge",
                    body = "Forwards 127.0.0.1:6000 to Termux's ssh-agent socket.",
                    buttonText = "Start bridge",
                    enabled = termuxInstalled && hasPermission,
                    onClick = {
                        TermuxExecutor.runCommand(ctx, SCRIPT_BRIDGE)
                        lastMessage = "Bridge start requested"
                    }
                )
            }

            lastMessage?.let { msg ->
                item {
                    Text(msg, style = SecrmType.Caption, color = Palette.Indigo,
                        modifier = Modifier.padding(top = Space.sm))
                }
            }

            item { ManualFallbackCard() }
        }
    }

    if (showLoginSheet) {
        BitwardenLoginSheet(
            onDismiss = { showLoginSheet = false },
            onStarted = { msg -> lastMessage = msg; showLoginSheet = false }
        )
    }
    if (showUnlockSheet) {
        BitwardenUnlockSheet(
            onDismiss = { showUnlockSheet = false },
            onStarted = { msg -> lastMessage = msg; showUnlockSheet = false }
        )
    }
}

// ── Components ────────────────────────────────────────────────

@Composable
private fun StatusCard(status: BitwardenStatus?, loading: Boolean, onRefresh: () -> Unit) {
    val (label, visual) = when {
        loading -> "Checking…" to ConnectionVisual.Connecting
        status == null -> "Unknown" to ConnectionVisual.Idle
        status.status == "missing" -> "Not installed" to ConnectionVisual.Warning
        status.status == "unauthenticated" -> "Not logged in" to ConnectionVisual.Warning
        status.status == "locked" -> "Locked" to ConnectionVisual.Warning
        status.status == "unlocked" -> "Unlocked" to ConnectionVisual.Up
        else -> status.status to ConnectionVisual.Idle
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(visual)
                Spacer(Modifier.width(Space.sm))
                Text("Bitwarden CLI",
                    style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Rounded.Refresh, "Refresh", tint = Palette.TextSecondary)
                }
            }
            Text(label, style = SecrmType.Body, color = Palette.TextSecondary)
            status?.userEmail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = SecrmType.MonoSmall, color = Palette.TextTertiary)
            }
        }
    }
}

@Composable
private fun PrereqRow(termuxInstalled: Boolean, hasPermission: Boolean) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (termuxInstalled) ConnectionVisual.Up else ConnectionVisual.Error)
                Spacer(Modifier.width(Space.sm))
                Text("Termux installed", style = SecrmType.Body, color = Palette.TextPrimary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (hasPermission) ConnectionVisual.Up else ConnectionVisual.Warning)
                Spacer(Modifier.width(Space.sm))
                Text("RUN_COMMAND permission", style = SecrmType.Body, color = Palette.TextPrimary)
            }
        }
    }
}

@Composable
private fun StepCard(
    n: Int, title: String, body: String, buttonText: String,
    enabled: Boolean, onClick: () -> Unit
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(24.dp)
                        .background(
                            if (enabled) Palette.Indigo else Palette.Surface3,
                            RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$n",
                        color = if (enabled) Color.White else Palette.TextTertiary,
                        fontWeight = FontWeight.Medium, style = SecrmType.Label)
                }
                Spacer(Modifier.width(Space.sm))
                Text(title,
                    style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary)
            }
            Text(body, style = SecrmType.Body, color = Palette.TextSecondary)
            Spacer(Modifier.height(Space.xs))
            PillButton(buttonText, PillStyle.Primary, Modifier.fillMaxWidth(),
                enabled = enabled, onClick = onClick)
        }
    }
}

@Composable
private fun ManualFallbackCard() {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val manual = """
/data/data/com.termux/files/usr/bin/pkg install -y nodejs-lts termux-services openssh socat jq
/data/data/com.termux/files/usr/bin/npm install -g @bitwarden/cli
bw login
bw unlock --raw | tee ~/.bw_session
sv-enable ssh-agent
sv up ssh-agent
export SSH_AUTH_SOCK=${'$'}PREFIX/var/run/ssh-agent.socket
/data/data/com.termux/files/usr/bin/node /data/data/com.termux/files/usr/bin/bw get item "work-ed25519" --session ${'$'}(cat ~/.bw_session) | jq -r '.sshKey.privateKey' | ssh-add -
nohup /data/data/com.termux/files/usr/bin/socat TCP-LISTEN:6000,bind=127.0.0.1,reuseaddr,fork UNIX-CONNECT:${'$'}SSH_AUTH_SOCK &
""".trimIndent()

    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.lg)) {
            Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Manual fallback",
                    style = SecrmType.Body.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", color = Palette.Indigo)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(Space.sm))
                Box(Modifier.fillMaxWidth()
                    .background(Palette.Void, RoundedCornerShape(8.dp))
                    .padding(Space.md)) {
                    Text(manual, style = SecrmType.MonoSmall, color = Palette.TextPrimary)
                }
                Spacer(Modifier.height(Space.sm))
                PillButton("Copy", PillStyle.Ghost, Modifier.fillMaxWidth()) {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("manual setup", manual))
                }
            }
        }
    }
}
