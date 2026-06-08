package com.codearm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*

// ═══════════════════════════════════════════════
// Theme
// ═══════════════════════════════════════════════
val BG      = Color(0xFF0D1117)
val SURFACE = Color(0xFF161B22)
val CARD    = Color(0xFF21262D)
val BLUE    = Color(0xFF2F81F7)
val GREEN   = Color(0xFF3FB950)
val RED     = Color(0xFFF78166)
val PURPLE  = Color(0xFFD2A8FF)
val GOLD    = Color(0xFFE3B341)
val BORDER  = Color(0xFF30363D)
val TEXT    = Color(0xFFE6EDF3)
val MUTED   = Color(0xFF8B949E)

// ═══════════════════════════════════════════════
// Modes
// ═══════════════════════════════════════════════
enum class Mode(
    val label: String,
    val emoji: String,
    val key: String,
    val hint: String,
    val color: Color
) {
    CODE   ("كتابة كود",    "⌨️", "code",    "اكتب لي كود احترافي...",      BLUE),
    DEBUG  ("تصحيح أخطاء", "🐛", "debug",   "الصق الخطأ أو الكود هنا...", RED),
    EXPLAIN("شرح وتحليل",  "📖", "explain", "ماذا تريد أن تفهم؟",         PURPLE),
    PROJECT("بناء مشروع",  "🚀", "project", "صف مشروعك بالتفصيل...",      GOLD),
}

// ═══════════════════════════════════════════════
// ViewModel
// ═══════════════════════════════════════════════
class ChatVM : ViewModel() {
    private val _msgs    = MutableStateFlow<List<Message>>(emptyList())
    val msgs: StateFlow<List<Message>> = _msgs.asStateFlow()

    private val _busy    = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _err     = MutableStateFlow<String?>(null)
    val err: StateFlow<String?> = _err.asStateFlow()

    private var call: okhttp3.Call? = null

    fun send(text: String, mode: Mode) {
        if (_busy.value || text.isBlank()) return
        val user = Message(role = "user", content = text.trim())
        val aId  = java.util.UUID.randomUUID().toString()
        val asst = Message(id = aId, role = "assistant", content = "")
        _msgs.update { it + user + asst }
        _busy.value = true
        _err.value  = null

        call = ApiClient.stream(
            messages = _msgs.value.dropLast(1),
            mode     = mode.key,
            onToken  = { tok ->
                _msgs.update { list ->
                    list.map { if (it.id == aId) it.copy(content = it.content + tok) else it }
                }
            },
            onDone  = { _busy.value = false },
            onError = { msg ->
                _msgs.update { list ->
                    list.map { if (it.id == aId) it.copy(content = "❌ $msg") else it }
                }
                _busy.value = false
                _err.value  = msg
            }
        )
    }

    fun stop()  { call?.cancel(); _busy.value = false }
    fun clear() { stop(); _msgs.value = emptyList(); _err.value = null }

    override fun onCleared() { call?.cancel() }
}

// ═══════════════════════════════════════════════
// Activity
// ═══════════════════════════════════════════════
class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge()
        setContent { App() }
    }
}

// ═══════════════════════════════════════════════
// Root composable
// ═══════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: ChatVM = viewModel()) {
    val msgs   by vm.msgs.collectAsState()
    val busy   by vm.busy.collectAsState()
    val err    by vm.err.collectAsState()

    var input  by remember { mutableStateOf("") }
    var mode   by remember { mutableStateOf(Mode.CODE) }
    val list   = rememberLazyListState()

    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1)
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background    = BG,
            surface       = SURFACE,
            primary       = BLUE,
            onPrimary     = Color.White,
            onBackground  = TEXT,
            onSurface     = TEXT,
            surfaceVariant = CARD,
            outline       = BORDER,
        )
    ) {
        Scaffold(
            containerColor = BG,
            topBar = { Bar(msgs, busy, mode) { vm.clear() } },
            bottomBar = {
                BottomBar(
                    mode    = mode,
                    input   = input,
                    busy    = busy,
                    onMode  = { mode = it },
                    onInput = { input = it },
                    onSend  = { if (busy) vm.stop() else { vm.send(input, mode); input = "" } }
                )
            }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                if (msgs.isEmpty()) {
                    Welcome(mode)
                } else {
                    LazyColumn(
                        state         = list,
                        modifier      = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(msgs, key = { it.id }) { msg ->
                            Bubble(msg, busy && msg == msgs.last() && msg.role == "assistant")
                        }
                    }
                }
                // error snack
                err?.let { e ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                        shape    = RoundedCornerShape(8.dp),
                        color    = RED.copy(alpha = .15f),
                        border   = BorderStroke(1.dp, RED.copy(alpha = .4f))
                    ) {
                        Text(e, color = RED, modifier = Modifier.padding(12.dp, 8.dp), fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
// Top bar
// ═══════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(msgs: List<Message>, busy: Boolean, mode: Mode, onClear: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = .4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "p"
    )
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = SURFACE),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(38.dp).background(mode.color.copy(.18f), CircleShape),
                    Alignment.Center
                ) {
                    Text(mode.emoji, fontSize = 18.sp)
                }
                Column {
                    Text("الذراع البرمجي", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TEXT)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (busy) Box(Modifier.size(7.dp).alpha(pulse).background(GREEN, CircleShape))
                        Text(
                            if (busy) "يفكر ويكتب..." else mode.label,
                            fontSize = 11.sp, color = if (busy) GREEN else MUTED
                        )
                    }
                }
            }
        },
        actions = {
            if (msgs.isNotEmpty()) {
                IconButton(onClear) {
                    Icon(Icons.Default.Delete, null, tint = MUTED)
                }
            }
        }
    )
}

// ═══════════════════════════════════════════════
// Bottom bar
// ═══════════════════════════════════════════════
@Composable
fun BottomBar(
    mode: Mode, input: String, busy: Boolean,
    onMode: (Mode) -> Unit, onInput: (String) -> Unit, onSend: () -> Unit
) {
    Column(Modifier.background(SURFACE).navigationBarsPadding()) {
        // mode chips
        LazyRow(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(Mode.entries.toTypedArray()) { m ->
                val sel = m == mode
                val bg by animateColorAsState(if (sel) m.color else CARD, label = "chip")
                FilterChip(
                    selected = sel, onClick = { onMode(m) },
                    label = { Text("${m.emoji} ${m.label}", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = bg,
                        selectedLabelColor     = Color.White,
                        containerColor         = CARD,
                        labelColor             = MUTED
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = sel,
                        borderColor = BORDER, selectedBorderColor = m.color
                    )
                )
            }
        }
        HorizontalDivider(color = BORDER)
        // input row
        Row(
            Modifier.fillMaxWidth().padding(12.dp, 10.dp),
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value         = input,
                onValueChange = onInput,
                modifier      = Modifier.weight(1f),
                placeholder   = { Text(mode.hint, color = MUTED, fontSize = 14.sp) },
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor    = mode.color,
                    unfocusedBorderColor  = BORDER,
                    focusedTextColor      = TEXT,
                    unfocusedTextColor    = TEXT,
                    cursorColor           = mode.color,
                    focusedContainerColor   = CARD,
                    unfocusedContainerColor = CARD
                ),
                shape    = RoundedCornerShape(16.dp),
                maxLines = 6,
                textStyle = TextStyle(fontSize = 14.sp)
            )
            val canSend = input.isNotBlank() || busy
            FilledIconButton(
                onClick  = onSend,
                modifier = Modifier.size(52.dp),
                colors   = IconButtonDefaults.filledIconButtonColors(
                    containerColor = when {
                        busy      -> RED
                        canSend   -> mode.color
                        else      -> CARD
                    }
                )
            ) {
                Icon(
                    if (busy) Icons.Default.Close else Icons.Default.Send,
                    null,
                    tint = if (canSend || busy) Color.White else MUTED
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════
// Welcome screen
// ═══════════════════════════════════════════════
@Composable
fun Welcome(mode: Mode) {
    val scale by rememberInfiniteTransition(label = "s").animateFloat(
        1f, 1.06f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "sc"
    )
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🤖", fontSize = 64.sp, modifier = Modifier.scale(scale))
        Spacer(Modifier.height(20.dp))
        Text("الذراع البرمجي", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TEXT, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "مساعد برمجي بذكاء اصطناعي\nتفكير عميق • بحث مباشر • كود احترافي",
            fontSize = 13.sp, color = MUTED, textAlign = TextAlign.Center, lineHeight = 22.sp
        )
        Spacer(Modifier.height(28.dp))
        Surface(
            shape  = RoundedCornerShape(14.dp),
            color  = mode.color.copy(.08f),
            border = BorderStroke(1.dp, mode.color.copy(.3f))
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${mode.emoji} ${mode.label}", color = mode.color, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                listOf(
                    "استجابات بث مباشر فورية",
                    "كود جاهز للتشغيل بالكامل",
                    "تفكير عميق وبحث Google",
                    "5 مفاتيح AI متناوبة"
                ).forEach { f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).background(mode.color, CircleShape))
                        Text(f, color = MUTED, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════
// Message bubble
// ═══════════════════════════════════════════════
@Composable
fun Bubble(msg: Message, streaming: Boolean) {
    val isUser = msg.role == "user"
    val clip   = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (!isUser) {
            Row(
                Modifier.padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier.size(22.dp).background(BLUE.copy(.2f), CircleShape),
                    Alignment.Center
                ) { Text("ذ", color = BLUE, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                Text("الذراع البرمجي", fontSize = 11.sp, color = MUTED)
                if (streaming) {
                    val a by rememberInfiniteTransition(label = "live").animateFloat(
                        .3f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "la"
                    )
                    Box(Modifier.size(7.dp).alpha(a).background(GREEN, CircleShape))
                }
            }
        }
        Surface(
            shape  = RoundedCornerShape(
                topStart    = if (isUser) 18.dp else 4.dp,
                topEnd      = if (isUser) 4.dp  else 18.dp,
                bottomStart = 18.dp, bottomEnd = 18.dp
            ),
            color  = if (isUser) BLUE.copy(.12f) else CARD,
            border = BorderStroke(1.dp, if (isUser) BLUE.copy(.25f) else BORDER),
            modifier = Modifier
                .widthIn(max = 310.dp)
                .combinedClickable(
                    onClick     = {},
                    onLongClick = {
                        clip.setPrimaryClip(
                            android.content.ClipData.newPlainText("msg", msg.content)
                        )
                    }
                )
        ) {
            if (isUser) {
                Text(
                    msg.content, color = TEXT, fontSize = 14.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(14.dp, 10.dp)
                )
            } else {
                AiContent(msg.content, streaming)
            }
        }
        if (isUser) {
            Text("أنت", fontSize = 10.sp, color = MUTED, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
        }
    }
}

// ═══════════════════════════════════════════════
// AI content with code blocks
// ═══════════════════════════════════════════════
@Composable
fun AiContent(content: String, streaming: Boolean) {
    if (content.isEmpty()) { TypingDots(); return }
    val segs = remember(content) { parseSegments(content) }
    Column(Modifier.padding(12.dp, 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segs.forEach { seg ->
            when (seg) {
                is Seg.Txt  -> if (seg.t.isNotBlank()) Text(seg.t.trim(), color = TEXT, fontSize = 14.sp, lineHeight = 22.sp)
                is Seg.Code -> CodeBox(seg.lang, seg.code)
            }
        }
        if (streaming) {
            val a by rememberInfiniteTransition(label = "cur").animateFloat(
                0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "ca"
            )
            Box(Modifier.size(8.dp, 15.dp).alpha(a).background(BLUE, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
fun TypingDots() {
    val inf = rememberInfiniteTransition(label = "td")
    Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by inf.animateFloat(
                .15f, 1f,
                infiniteRepeatable(tween(450, delayMillis = i * 150), RepeatMode.Reverse),
                label = "d$i"
            )
            Box(Modifier.size(8.dp).alpha(a).background(BLUE, CircleShape))
        }
    }
}

sealed class Seg {
    data class Txt(val t: String) : Seg()
    data class Code(val lang: String, val code: String) : Seg()
}

fun parseSegments(content: String): List<Seg> {
    val result  = mutableListOf<Seg>()
    val pattern = Regex("```(\\w*)\\n?([\\s\\S]*?)```", RegexOption.MULTILINE)
    var last    = 0
    for (m in pattern.findAll(content)) {
        if (m.range.first > last) result.add(Seg.Txt(content.substring(last, m.range.first)))
        result.add(Seg.Code(m.groupValues[1].ifEmpty { "code" }, m.groupValues[2].trimEnd()))
        last = m.range.last + 1
    }
    if (last < content.length) result.add(Seg.Txt(content.substring(last)))
    return result
}

@Composable
fun CodeBox(lang: String, code: String) {
    val ctx   = LocalContext.current
    val clip  = ctx.getSystemService(android.content.ClipboardManager::class.java)
    var copied by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()

    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(2000); copied = false } }

    Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF0D1117), border = BorderStroke(1.dp, BORDER)) {
        Column {
            // header
            Row(
                Modifier.fillMaxWidth().background(SURFACE).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(lang.ifEmpty { "code" }, fontSize = 11.sp, color = MUTED, fontFamily = FontFamily.Monospace)
                TextButton(
                    onClick = {
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("code", code))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        null, Modifier.size(13.dp),
                        tint = if (copied) GREEN else MUTED
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) "تم" else "نسخ", fontSize = 11.sp, color = if (copied) GREEN else MUTED)
                }
            }
            // code
            Box(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(12.dp)) {
                Text(code, color = Color(0xFFE6EDF3), fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp)
            }
        }
    }
}
