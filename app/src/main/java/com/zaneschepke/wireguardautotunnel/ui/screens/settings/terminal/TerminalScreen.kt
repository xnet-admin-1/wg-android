package com.zaneschepke.wireguardautotunnel.ui.screens.settings.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zaneschepke.wireguardautotunnel.core.terminal.ShellDiscovery
import com.zaneschepke.wireguardautotunnel.core.terminal.backend.TerminalSession
import com.zaneschepke.wireguardautotunnel.core.terminal.view.TerminalView
import com.zaneschepke.wireguardautotunnel.core.terminal.view.TerminalViewClient
import java.lang.ref.WeakReference

private object SessionHolder {
    private val sessions = mutableMapOf<String, TerminalSession>()
    var viewRef: WeakReference<TerminalView>? = null
    var ctrlDown = false
    var altDown = false

    private val callback = object : TerminalSession.SessionChangedCallback {
        override fun onTextChanged(s: TerminalSession) { viewRef?.get()?.let { v -> v.post { v.onScreenUpdated() } } }
        override fun onTitleChanged(s: TerminalSession) {}
        override fun onSessionFinished(s: TerminalSession) { sessions.entries.removeAll { it.value === s } }
        override fun onClipboardText(s: TerminalSession, text: String) {
            viewRef?.get()?.let { v ->
                v.post { (v.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("terminal", text)) }
            }
        }
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) {}
    }

    fun getOrCreate(shell: ShellDiscovery.Shell): TerminalSession = sessions.getOrPut(shell.id) {
        TerminalSession(shell.command, shell.cwd, shell.args, shell.env, callback)
    }
}

@Composable
fun TerminalScreen() {
    val ctx = LocalContext.current
    val shells = remember { ShellDiscovery.getShells(ctx) }
    val availableShells = shells.filter { it.available }
    var selectedId by remember { mutableStateOf(availableShells.firstOrNull()?.id ?: "sh") }

    Column(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            availableShells.forEach { shell ->
                TextButton(onClick = { selectedId = shell.id }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(shell.name, color = if (shell.id == selectedId) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Gray, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val shell = shells.firstOrNull { it.id == selectedId && it.available }
        if (shell != null) {
            TerminalViewComposable(shell, Modifier.weight(1f).fillMaxWidth())
            ExtraKeysBar()
        }
    }
}

@Composable
private fun TerminalViewComposable(shell: ShellDiscovery.Shell, modifier: Modifier) {
    val session = remember(shell.id) { SessionHolder.getOrCreate(shell) }

    AndroidView(
        factory = { context ->
            TerminalView(context, null).apply {
                val dp = context.resources.displayMetrics.scaledDensity
                setTextSize((13 * dp).toInt())
                setBackgroundColor(Color.BLACK)
                isFocusable = true; isFocusableInTouchMode = true; keepScreenOn = true
                attachSession(session)
                SessionHolder.viewRef = WeakReference(this)
                setTerminalViewClient(object : TerminalViewClient {
                    override fun onScale(scale: Float): Float = scale
                    override fun onSingleTapUp(e: MotionEvent?) {
                        requestFocus()
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this@apply, 0)
                    }
                    override fun shouldBackButtonBeMappedToEscape() = false
                    override fun copyModeChanged(copyMode: Boolean) {}
                    override fun onKeyDown(keyCode: Int, e: KeyEvent?, s: TerminalSession?) = false
                    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
                    override fun readControlKey() = SessionHolder.ctrlDown
                    override fun readAltKey() = SessionHolder.altDown
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession?) = false
                    override fun onLongPress(event: MotionEvent?) = false
                })
                requestFocus()
            }
        },
        update = { view -> SessionHolder.viewRef = WeakReference(view); view.attachSession(session); view.post { view.onScreenUpdated() } },
        modifier = modifier,
    )
}

@Composable
private fun ExtraKeysBar() {
    AndroidView(
        factory = { context ->
            val dp = context.resources.displayMetrics.density
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#0F1729"))
                val keys = listOf("ESC" to "\u001b", "TAB" to "\t", "CTRL" to "", "ALT" to "", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C")
                keys.forEach { (label, seq) ->
                    val tv = TextView(context).apply {
                        text = label; textSize = 11f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, (38 * dp).toInt(), 1f).apply { marginEnd = (1 * dp).toInt() }
                    }
                    if (label == "CTRL" || label == "ALT") {
                        tv.setOnClickListener {
                            when (label) { "CTRL" -> SessionHolder.ctrlDown = !SessionHolder.ctrlDown; "ALT" -> SessionHolder.altDown = !SessionHolder.altDown }
                            tv.setTextColor(if ((label == "CTRL" && SessionHolder.ctrlDown) || (label == "ALT" && SessionHolder.altDown)) Color.GREEN else Color.WHITE)
                        }
                    } else {
                        tv.setOnClickListener {
                            SessionHolder.viewRef?.get()?.let { view ->
                                val field = view.javaClass.getDeclaredField("mTermSession")
                                field.isAccessible = true
                                (field.get(view) as? TerminalSession)?.write(seq)
                            }
                        }
                    }
                    addView(tv)
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
