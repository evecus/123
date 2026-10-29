package com.cablebee.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cablebee.assistant.services.AdbService
import com.cablebee.assistant.ui.theme.Bg0
import com.cablebee.assistant.ui.theme.Bg1
import com.cablebee.assistant.ui.theme.Bg3
import com.cablebee.assistant.ui.theme.Danger
import com.cablebee.assistant.ui.theme.JetBrainsMono
import com.cablebee.assistant.ui.theme.Primary
import com.cablebee.assistant.ui.theme.SpaceMono
import com.cablebee.assistant.ui.theme.TextMuted
import com.cablebee.assistant.ui.theme.TextPrimary
import com.cablebee.assistant.ui.theme.TextSecondary
import kotlinx.coroutines.launch

// ── Shell Entry Model ─────────────────────────────────────────────────────────

enum class EntryType { COMMAND, OUTPUT }

data class ShellEntry(
    val type: EntryType,
    val content: String,
    val isError: Boolean = false,
    val muted: Boolean = false,
    val cwd: String? = null
)

// ── Quick Commands ────────────────────────────────────────────────────────────

private val QuickCommands = listOf(
    "ps" to "processes",
    "top -n 1" to "cpu",
    "df -h" to "disk",
    "netstat" to "net",
    "logcat -d" to "log",
    "dumpsys battery" to "battery",
    "getprop" to "props",
    "pm list packages -3" to "apps",
    "wm size" to "screen",
    "wm density" to "dpi",
    "id" to "whoami",
    "uname -a" to "kernel",
)

// ── ShellScreen ───────────────────────────────────────────────────────────────

@Composable
fun ShellScreen(
    adbService: AdbService,
    onActionsChanged: (@Composable RowScope.() -> Unit) -> Unit = {},
    actionsVersion: Int = 0
) {
    val entries = remember { mutableStateListOf<ShellEntry>() }
    val history = remember { mutableListOf<String>() }
    val historyIndex = remember { mutableIntStateOf(-1) }
    var running by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf("/") }
    var cmdText by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // ── AppBar 动作：清空 + 复制全部（对应 Flutter _pushActions）──────────────
    LaunchedEffect(actionsVersion) {
        onActionsChanged {
            IconButton(
                onClick = {
                    entries.clear()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.DeleteSweep,
                    contentDescription = "清空",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = {
                    val all = entries.joinToString("\n") { it.content }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("shell_output", all))
                    Toast.makeText(context, "已复制全部输出", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.ContentCopy,
                    contentDescription = "复制全部",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    // Auto-scroll to bottom when entries change
    LaunchedEffect(entries.size, running) {
        if (entries.isNotEmpty() || running) {
            val targetIndex = entries.size + (if (running) 1 else 0) - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    // Initialize cwd from device
    LaunchedEffect(Unit) {
        try {
            val res = adbService.shell("pwd")
            val pwd = res.stdout.trim()
            if (pwd.startsWith("/")) cwd = pwd
        } catch (_: Exception) {
        }
    }

    // ── Path helpers ──────────────────────────────────────────────────────

    fun normalize(path: String): String {
        val parts = mutableListOf<String>()
        for (seg in path.split("/")) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                if (parts.isNotEmpty()) parts.removeLast()
            } else {
                parts.add(seg)
            }
        }
        return "/${parts.joinToString("/")}"
    }

    fun parent(path: String): String {
        if (path == "/") return "/"
        val parts = path.split("/").dropLast(1)
        val p = parts.joinToString("/")
        return if (p.isEmpty()) "/" else p
    }

    fun resolveCd(arg: String): String {
        val trimmed = arg.trim()
        if (trimmed.isEmpty() || trimmed == "~") return "/sdcard"
        if (trimmed.startsWith("/")) return normalize(trimmed)
        if (trimmed == "..") return parent(cwd)
        if (trimmed == ".") return cwd
        return normalize("$cwd/$trimmed")
    }

    // ── Scroll helper ─────────────────────────────────────────────────────

    fun scrollToBottom() {
        val targetIndex = entries.size + (if (running) 1 else 0) - 1
        if (targetIndex >= 0) {
            scope.launch {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    // ── Run command ───────────────────────────────────────────────────────

    fun run(rawCmd: String) {
        val cmd = rawCmd.trim()
        if (cmd.isEmpty()) return

        if (history.isEmpty() || history.last() != cmd) history.add(cmd)
        historyIndex.intValue = -1

        // ── Handle pure cd command ────────────────────────────────────────
        val cdRegex = Regex("^cd\\s*(.*)$")
        val cdMatch = cdRegex.find(cmd)
        if (cdMatch != null) {
            val arg = cdMatch.groupValues[1].trim()
            val candidate = resolveCd(arg)

            entries.add(ShellEntry(EntryType.COMMAND, cmd, cwd = cwd))
            running = true
            cmdText = ""
            scrollToBottom()

            scope.launch {
                try {
                    val checkRes = adbService.shell("[ -d \"$candidate\" ] && echo OK || echo FAIL")
                    val ok = checkRes.stdout.trim() == "OK"
                    if (ok) {
                        cwd = candidate
                        entries.add(ShellEntry(EntryType.OUTPUT, "→ $cwd", muted = true))
                    } else {
                        entries.add(
                            ShellEntry(
                                EntryType.OUTPUT,
                                "cd: $candidate: No such file or directory",
                                isError = true
                            )
                        )
                    }
                } catch (e: Exception) {
                    entries.add(
                        ShellEntry(EntryType.OUTPUT, e.message ?: "Error", isError = true)
                    )
                }
                running = false
                scrollToBottom()
            }
            return
        }

        // ── Regular command: streaming output ─────────────────────────────
        entries.add(ShellEntry(EntryType.COMMAND, cmd, cwd = cwd))
        running = true
        cmdText = ""
        scrollToBottom()

        val wrapped = "cd \"$cwd\" 2>/dev/null; $cmd"

        scope.launch {
            var hasOutput = false
            try {
                adbService.shellStream(wrapped).collect { line ->
                    hasOutput = true
                    entries.add(ShellEntry(EntryType.OUTPUT, line))
                }
            } catch (e: Exception) {
                entries.add(ShellEntry(EntryType.OUTPUT, e.message ?: "Error", isError = true))
                hasOutput = true
            }

            // Sync cwd if command contains cd
            if (cmd.contains("cd ")) {
                try {
                    val pwdRes = adbService.shell("cd \"$cwd\" 2>/dev/null; $cmd; pwd")
                    val lines = pwdRes.stdout.trim().lines()
                    val last = lines.lastOrNull()?.trim() ?: ""
                    if (last.startsWith("/")) cwd = last
                } catch (_: Exception) {
                }
            }

            if (!hasOutput) {
                entries.add(ShellEntry(EntryType.OUTPUT, "(no output)", muted = true))
            }
            running = false
            scrollToBottom()
        }
    }

    // ── History navigation ────────────────────────────────────────────────

    fun navigateHistory(dir: Int) {
        if (history.isEmpty()) return
        historyIndex.intValue = (historyIndex.intValue - dir).coerceIn(-1, history.size - 1)
        if (historyIndex.intValue == -1) {
            cmdText = ""
        } else {
            val idx = history.size - 1 - historyIndex.intValue
            cmdText = history[idx]
        }
    }

    // ── Clear ─────────────────────────────────────────────────────────────

    fun clear() {
        entries.clear()
    }

    // ── Copy all ──────────────────────────────────────────────────────────

    fun copyAll() {
        val all = entries.joinToString("\n") { it.content }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("shell_output", all))
    }

    // ── Build UI ──────────────────────────────────────────────────────────

    Column(modifier = Modifier.fillMaxSize().background(Bg0)) {

        // ── Quick commands row ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().height(38.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Scrollable quick command chips
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickCommands.forEach { (cmd, label) ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Bg1,
                        border = BorderStroke(1.dp, Bg3),
                        modifier = Modifier.clickable { run(cmd) }
                    ) {
                        Text(
                            label,
                            style = TextStyle(
                                fontFamily = SpaceMono,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
        }

        Divider(color = Bg3, thickness = 1.dp)

        // ── Output area ───────────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "$ ${adbService.selectedDevice.value?.serial ?: ""}",
                        style = TextStyle(
                            fontFamily = JetBrainsMono,
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Type a command below",
                        style = TextStyle(
                            fontFamily = JetBrainsMono,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(entries) { entry ->
                        EntryWidget(entry = entry)
                    }
                    if (running) {
                        item {
                            Row(
                                modifier = Modifier.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = Primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    "running...",
                                    style = TextStyle(
                                        fontFamily = JetBrainsMono,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        Divider(color = Bg3, thickness = 1.dp)

        // ── Path prompt bar ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg1)
                .padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.FolderOpen,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                cwd,
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = Primary
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Quick directory shortcuts
            CwdShortcut(label = "/", onTap = { run("cd /") })
            CwdShortcut(label = "~", onTap = { run("cd /sdcard") })
            CwdShortcut(label = "..", onTap = { run("cd ..") })
        }

        // ── Input bar ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Bg1)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$ ",
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 14.sp,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            )
            BasicTextField(
                value = cmdText,
                onValueChange = { cmdText = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 13.sp,
                    color = TextPrimary
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    run(cmdText)
                }),
                decorationBox = { innerTextField ->
                    Box {
                        if (cmdText.isEmpty()) {
                            Text(
                                "输入命令...",
                                style = TextStyle(
                                    fontFamily = JetBrainsMono,
                                    fontSize = 13.sp,
                                    color = TextMuted
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )
            IconButton(
                onClick = { navigateHistory(1) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "History up",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = { navigateHistory(-1) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "History down",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primary.copy(alpha = 0.15f))
                    .border(1.dp, Primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { run(cmdText) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Send,
                    contentDescription = "Send",
                    tint = Primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── Cwd Shortcut ──────────────────────────────────────────────────────────────

@Composable
private fun CwdShortcut(label: String, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Primary.copy(alpha = 0.08f))
            .border(1.dp, Primary.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                color = Primary
            )
        )
    }
}

// ── Entry Widget ──────────────────────────────────────────────────────────────

@Composable
private fun EntryWidget(entry: ShellEntry) {
    if (entry.type == EntryType.COMMAND) {
        Column(modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)) {
            // Path hint (dim color)
            if (entry.cwd != null) {
                Text(
                    entry.cwd,
                    style = TextStyle(
                        fontFamily = JetBrainsMono,
                        fontSize = 10.sp,
                        color = TextMuted
                    ),
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    "$ ",
                    style = TextStyle(
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    entry.content,
                    style = TextStyle(
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    } else {
        // Output entry
        val color: Color = when {
            entry.muted -> TextMuted
            entry.isError -> Danger.copy(alpha = 0.85f)
            else -> TextSecondary
        }
        SelectionContainer {
            Text(
                entry.content,
                style = TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    color = color,
                    lineHeight = 18.6.sp
                ),
                modifier = Modifier.padding(bottom = 8.dp, start = 16.dp)
            )
        }
    }
}
