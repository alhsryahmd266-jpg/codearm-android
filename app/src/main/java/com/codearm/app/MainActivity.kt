@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.codearm.app

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ════════════════════════════════════════════
//  COLORS
// ════════════════════════════════════════════
val C_BG      = Color(0xFF0A0A0F)
val C_SURF    = Color(0xFF13131A)
val C_CARD    = Color(0xFF1C1C27)
val C_BORDER  = Color(0xFF2A2A3A)
val C_BLUE    = Color(0xFF5B8FEF)
val C_GREEN   = Color(0xFF3FB950)
val C_RED     = Color(0xFFFF6B6B)
val C_ORANGE  = Color(0xFFF5A623)
val C_PURPLE  = Color(0xFFB57BFF)
val C_TEXT    = Color(0xFFEAEBF0)
val C_MUTED   = Color(0xFF6B7080)
val C_THINK   = Color(0xFF2D2D40)

// ════════════════════════════════════════════
//  DATA
// ════════════════════════════════════════════
enum class Mode(val label: String, val emoji: String, val key: String, val color: Color, val hint: String) {
    CODE   ("كتابة كود",    "⌨️", "code",    C_BLUE,   "اكتب لي كود احترافي جاهز للتشغيل..."),
    DEBUG  ("تصحيح خطأ",   "🔍", "debug",   C_RED,    "الصق الخطأ أو الكود المعطل هنا..."),
    EXPLAIN("شرح وتحليل",  "📖", "explain", C_PURPLE, "ماذا تريد أن تفهم أو تحلل؟"),
    PROJECT("بناء مشروع",  "🚀", "project", C_ORANGE, "صف مشروعك بالتفصيل وسأبنيه كاملاً..."),
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,   // "user" | "assistant"
    val content: String,
    val thinking: String = "",
    val ts: Long = System.currentTimeMillis()
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage>,
    val mode: Mode,
    val ts: Long = System.currentTimeMillis()
)

// ════════════════════════════════════════════
//  STORAGE
// ════════════════════════════════════════════
object Storage {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("codearm_v2", Context.MODE_PRIVATE)
    }

    fun saveConversations(convs: List<Conversation>) {
        val arr = JSONArray()
        convs.takeLast(50).forEach { c ->
            val msgsArr = JSONArray()
            c.messages.forEach { m ->
                msgsArr.put(JSONObject()
                    .put("id", m.id).put("role", m.role)
                    .put("content", m.content).put("thinking", m.thinking).put("ts", m.ts))
            }
            arr.put(JSONObject()
                .put("id", c.id).put("title", c.title)
                .put("mode", c.mode.key).put("ts", c.ts).put("msgs", msgsArr))
        }
        prefs.edit().putString("convs", arr.toString()).apply()
    }

    fun loadConversations(): List<Conversation> {
        val raw = prefs.getString("convs", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val msgsArr = obj.getJSONArray("msgs")
                val msgs = (0 until msgsArr.length()).map { j ->
                    val m = msgsArr.getJSONObject(j)
                    ChatMessage(m.getString("id"), m.getString("role"),
                        m.getString("content"), m.optString("thinking",""), m.getLong("ts"))
                }
                val modeKey = obj.getString("mode")
                Conversation(obj.getString("id"), obj.getString("title"), msgs,
                    Mode.entries.firstOrNull { it.key == modeKey } ?: Mode.CODE, obj.getLong("ts"))
            }.sortedByDescending { it.ts }
        }.getOrDefault(emptyList())
    }
}

// ════════════════════════════════════════════
//  VIEW MODEL
// ════════════════════════════════════════════
class VM : ViewModel() {
    private val _convs   = MutableStateFlow<List<Conversation>>(emptyList())
    val convs: StateFlow<List<Conversation>> = _convs.asStateFlow()

    private val _active  = MutableStateFlow<Conversation?>(null)
    val active: StateFlow<Conversation?> = _active.asStateFlow()

    private val _busy    = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _mode    = MutableStateFlow(Mode.CODE)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _screen  = MutableStateFlow(Screen.CHAT)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private var call: okhttp3.Call? = null

    fun init(ctx: Context) {
        Storage.init(ctx)
        _convs.value = Storage.loadConversations()
    }

    fun setMode(m: Mode) { _mode.value = m }
    fun setScreen(s: Screen) { _screen.value = s }

    fun newChat() {
        _active.value = null
        _screen.value = Screen.CHAT
    }

    fun openConv(c: Conversation) {
        _active.value = c
        _mode.value = c.mode
        _screen.value = Screen.CHAT
    }

    fun deleteConv(id: String) {
        _convs.update { it.filter { c -> c.id != id } }
        if (_active.value?.id == id) _active.value = null
        Storage.saveConversations(_convs.value)
    }

    fun send(text: String) {
        if (_busy.value || text.isBlank()) return
        val mode = _mode.value
        val userMsg = ChatMessage(role = "user", content = text.trim())
        val aId = UUID.randomUUID().toString()
        val asst = ChatMessage(id = aId, role = "assistant", content = "", thinking = "")

        val current = _active.value
        val history = (current?.messages ?: emptyList()) + userMsg
        val conv = current?.copy(messages = history + asst)
            ?: Conversation(
                title = text.trim().take(40),
                messages = history + asst,
                mode = mode
            )
        _active.value = conv
        _busy.value = true

        call = ApiClient.stream(
            messages = history,
            mode = mode.key,
            onToken = { tok ->
                _active.update { it?.let { c ->
                    c.copy(messages = c.messages.map { m ->
                        if (m.id == aId) m.copy(content = m.content + tok) else m
                    })
                }}
            },
            onThink = { thk ->
                _active.update { it?.let { c ->
                    c.copy(messages = c.messages.map { m ->
                        if (m.id == aId) m.copy(thinking = m.thinking + thk) else m
                    })
                }}
            },
            onDone = {
                _busy.value = false
                _active.value?.let { c ->
                    val updated = _convs.value.filter { it.id != c.id } + c
                    _convs.value = updated.sortedByDescending { it.ts }
                    Storage.saveConversations(_convs.value)
                }
            },
            onError = { err ->
                _active.update { it?.let { c ->
                    c.copy(messages = c.messages.map { m ->
                        if (m.id == aId) m.copy(content = "❌ $err") else m
                    })
                }}
                _busy.value = false
            }
        )
    }

    fun stop() { call?.cancel(); _busy.value = false }
    fun clearChat() {
        stop()
        _active.value = null
    }

    override fun onCleared() { call?.cancel() }
}

enum class Screen { CHAT, HISTORY, SETTINGS }

// ════════════════════════════════════════════
//  ACTIVITY
// ════════════════════════════════════════════
class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        enableEdgeToEdge()
        setContent { CodeArmApp() }
    }
}

// ════════════════════════════════════════════
//  ROOT APP
// ════════════════════════════════════════════
@Composable
fun CodeArmApp(vm: VM = viewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.init(ctx) }

    val screen by vm.screen.collectAsState()
    val mode   by vm.mode.collectAsState()
    val active by vm.active.collectAsState()
    val busy   by vm.busy.collectAsState()
    val convs  by vm.convs.collectAsState()

    MaterialTheme(
        colorScheme = darkColorScheme(
            background    = C_BG,
            surface       = C_SURF,
            primary       = C_BLUE,
            onPrimary     = Color.White,
            onBackground  = C_TEXT,
            onSurface     = C_TEXT,
            surfaceVariant = C_CARD,
            outline       = C_BORDER,
        )
    ) {
        Scaffold(
            containerColor = C_BG,
            bottomBar = { NavBar(screen) { vm.setScreen(it) } }
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    }
                ) { s ->
                    when (s) {
                        Screen.CHAT     -> ChatScreen(vm, active, mode, busy)
                        Screen.HISTORY  -> HistoryScreen(convs, vm::openConv, vm::deleteConv, vm::newChat)
                        Screen.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
    }
}

// ════════════════════════════════════════════
//  NAV BAR
// ════════════════════════════════════════════
@Composable
fun NavBar(screen: Screen, onNav: (Screen) -> Unit) {
    NavigationBar(containerColor = C_SURF, tonalElevation = 0.dp) {
        listOf(
            Triple(Screen.CHAT,     Icons.Default.Chat,     "محادثة"),
            Triple(Screen.HISTORY,  Icons.Default.History,  "السجل"),
            Triple(Screen.SETTINGS, Icons.Default.Settings, "الإعدادات"),
        ).forEach { (s, icon, label) ->
            NavigationBarItem(
                selected = screen == s,
                onClick  = { onNav(s) },
                icon     = { Icon(icon, null) },
                label    = { Text(label, fontSize = 11.sp) },
                colors   = NavigationBarItemDefaults.colors(
                    selectedIconColor    = C_BLUE,
                    selectedTextColor    = C_BLUE,
                    unselectedIconColor  = C_MUTED,
                    unselectedTextColor  = C_MUTED,
                    indicatorColor       = C_BLUE.copy(alpha = .15f)
                )
            )
        }
    }
}

// ════════════════════════════════════════════
//  CHAT SCREEN
// ════════════════════════════════════════════
@Composable
fun ChatScreen(vm: VM, conv: Conversation?, mode: Mode, busy: Boolean) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val msgs = conv?.messages ?: emptyList()

    LaunchedEffect(msgs.size, busy) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    Column(Modifier.fillMaxSize().background(C_BG)) {
        ChatTopBar(conv, mode, busy, vm::clearChat, vm::newChat)
        Box(Modifier.weight(1f)) {
            if (msgs.isEmpty()) {
                WelcomeScreen(mode)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(msgs, key = { it.id }) { msg ->
                        MessageRow(msg, busy && msg == msgs.last() && msg.role == "assistant")
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        InputArea(mode, input, busy, onInput = { input = it }, onMode = vm::setMode) {
            if (busy) vm.stop()
            else if (input.isNotBlank()) { vm.send(input); input = "" }
        }
    }
}

@Composable
fun ChatTopBar(conv: Conversation?, mode: Mode, busy: Boolean, onClear: () -> Unit, onNew: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), ""
    )
    Surface(color = C_SURF, shadowElevation = 4.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo
            Box(
                Modifier.size(40.dp).background(
                    Brush.radialGradient(listOf(mode.color.copy(.3f), mode.color.copy(.05f))),
                    CircleShape
                ).border(1.dp, mode.color.copy(.4f), CircleShape),
                Alignment.Center
            ) { Text(mode.emoji, fontSize = 18.sp) }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    conv?.title?.take(30) ?: "الذراع البرمجي",
                    color = C_TEXT, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (busy) {
                        Box(Modifier.size(7.dp).alpha(pulse).background(C_GREEN, CircleShape))
                        Text("يفكر ويكتب...", fontSize = 11.sp, color = C_GREEN)
                    } else {
                        Box(Modifier.size(7.dp).background(C_GREEN, CircleShape))
                        Text("Gemini 2.5 Pro • تفكير عميق", fontSize = 11.sp, color = C_MUTED)
                    }
                }
            }

            IconButton(onNew) { Icon(Icons.Default.Add, null, tint = C_MUTED) }
            if (conv != null) {
                IconButton(onClear) { Icon(Icons.Default.Delete, null, tint = C_MUTED) }
            }
        }
    }
}

// ════════════════════════════════════════════
//  INPUT AREA
// ════════════════════════════════════════════
@Composable
fun InputArea(mode: Mode, input: String, busy: Boolean, onInput: (String) -> Unit, onMode: (Mode) -> Unit, onSend: () -> Unit) {
    Surface(color = C_SURF) {
        Column(Modifier.navigationBarsPadding()) {
            LazyRow(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(Mode.entries.toTypedArray()) { m ->
                    ModeChip(m, m == mode) { onMode(m) }
                }
            }
            HorizontalDivider(color = C_BORDER, modifier = Modifier.padding(top = 8.dp))
            // Input row
            Row(
                Modifier.padding(12.dp, 8.dp, 12.dp, 12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input, onValueChange = onInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(mode.hint, color = C_MUTED, fontSize = 13.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor    = mode.color,
                        unfocusedBorderColor  = C_BORDER,
                        focusedTextColor      = C_TEXT,
                        unfocusedTextColor    = C_TEXT,
                        cursorColor           = mode.color,
                        focusedContainerColor   = C_CARD,
                        unfocusedContainerColor = C_CARD,
                    ),
                    shape = RoundedCornerShape(18.dp),
                    maxLines = 8,
                    textStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
                )
                FloatingActionButton(
                    onClick  = onSend,
                    modifier = Modifier.size(50.dp),
                    containerColor = if (busy) C_RED else if (input.isNotBlank()) mode.color else C_CARD,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp)
                ) {
                    Icon(
                        if (busy) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        null, tint = if (busy || input.isNotBlank()) Color.White else C_MUTED,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ModeChip(mode: Mode, selected: Boolean, onClick: () -> Unit) {
    val bg     by animateColorAsState(if (selected) mode.color.copy(.15f) else Color.Transparent, label = "")
    val border by animateColorAsState(if (selected) mode.color else C_BORDER, label = "")
    Surface(
        onClick = onClick,
        shape   = RoundedCornerShape(20.dp),
        color   = bg,
        border  = BorderStroke(1.dp, border)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(mode.emoji, fontSize = 13.sp)
            Text(mode.label, fontSize = 12.sp,
                color = if (selected) mode.color else C_MUTED,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

// ════════════════════════════════════════════
//  WELCOME
// ════════════════════════════════════════════
@Composable
fun WelcomeScreen(mode: Mode) {
    val float by rememberInfiniteTransition(label = "").animateFloat(
        -8f, 8f, infiniteRepeatable(tween(3000, easing = EaseInOutSine), RepeatMode.Reverse), ""
    )
    Column(
        Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🤖", fontSize = 72.sp, modifier = Modifier.offset(y = float.dp))
        Spacer(Modifier.height(20.dp))
        Text("الذراع البرمجي", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = C_TEXT, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("مساعد برمجي بذكاء اصطناعي من الجيل القادم", fontSize = 13.sp, color = C_MUTED, textAlign = TextAlign.Center)

        Spacer(Modifier.height(32.dp))

        // Capability cards
        listOf(
            Triple("🧠", "تفكير عميق", "يفكر خطوة بخطوة قبل الإجابة — ${16000} token thinking"),
            Triple("🔍", "بحث Google", "يبحث في الإنترنت ويجيب بأحدث المعلومات"),
            Triple("⚡", "بث فوري", "يكتب الإجابة في الوقت الحقيقي أمامك مباشرة"),
            Triple("🔑", "5 مفاتيح AI", "تناوب تلقائي على 5 مفاتيح لضمان عدم الانقطاع"),
        ).forEach { (icon, title, desc) ->
            Surface(
                shape  = RoundedCornerShape(14.dp),
                color  = C_CARD,
                border = BorderStroke(1.dp, C_BORDER),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(icon, fontSize = 24.sp)
                    Column {
                        Text(title, color = mode.color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(desc, color = C_MUTED, fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("💬 ${mode.hint}", color = C_MUTED, fontSize = 13.sp, textAlign = TextAlign.Center)
    }
}

// ════════════════════════════════════════════
//  MESSAGE ROW
// ════════════════════════════════════════════
@Composable
fun MessageRow(msg: ChatMessage, streaming: Boolean) {
    val isUser = msg.role == "user"
    val clip = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    if (isUser) {
        // User message - right aligned
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            Surface(
                shape  = RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
                color  = C_BLUE.copy(.15f),
                border = BorderStroke(1.dp, C_BLUE.copy(.2f)),
                modifier = Modifier.widthIn(max = 300.dp).combinedClickable(
                    onClick = {}, onLongClick = {
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.content))
                    }
                )
            ) {
                Text(msg.content, color = C_TEXT, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.padding(14.dp, 10.dp))
            }
            Text(fmt.format(Date(msg.ts)), color = C_MUTED, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
        }
    } else {
        // Assistant message - left aligned
        Column(Modifier.fillMaxWidth()) {
            // Header
            Row(
                Modifier.padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    Modifier.size(26.dp).background(
                        Brush.radialGradient(listOf(C_BLUE.copy(.4f), C_BLUE.copy(.1f))), CircleShape
                    ).border(1.dp, C_BLUE.copy(.5f), CircleShape),
                    Alignment.Center
                ) { Text("ذ", color = C_BLUE, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Text("الذراع البرمجي", color = C_MUTED, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                if (streaming) {
                    val a by rememberInfiniteTransition(label = "").animateFloat(
                        .3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), ""
                    )
                    Text("●", color = C_GREEN.copy(a), fontSize = 10.sp)
                    Text("يفكر...", color = C_GREEN, fontSize = 10.sp)
                }
            }

            // Thinking block
            if (msg.thinking.isNotEmpty()) {
                ThinkingBlock(msg.thinking, streaming && msg.content.isEmpty())
                Spacer(Modifier.height(8.dp))
            }

            // Content
            if (msg.content.isEmpty() && streaming) {
                TypingDots()
            } else if (msg.content.isNotEmpty()) {
                AiContentBlock(msg.content, streaming, clip)
            }

            Text(fmt.format(Date(msg.ts)), color = C_MUTED, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, start = 4.dp))
        }
    }
}

// ════════════════════════════════════════════
//  THINKING BLOCK
// ════════════════════════════════════════════
@Composable
fun ThinkingBlock(thinking: String, active: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "").animateFloat(
        .4f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), ""
    )

    Surface(
        shape  = RoundedCornerShape(10.dp),
        color  = C_THINK,
        border = BorderStroke(1.dp, C_PURPLE.copy(.3f))
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(10.dp, 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (active) Text("⚡", fontSize = 13.sp, modifier = Modifier.alpha(pulse))
                else Text("🧠", fontSize = 13.sp)
                Text(
                    if (active) "يفكر..." else "التفكير الداخلي (${thinking.length} حرف)",
                    color = C_PURPLE, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, tint = C_PURPLE, modifier = Modifier.size(16.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    thinking,
                    color = C_MUTED,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(10.dp, 0.dp, 10.dp, 10.dp)
                )
            }
        }
    }
}

// ════════════════════════════════════════════
//  AI CONTENT
// ════════════════════════════════════════════
@Composable
fun AiContentBlock(content: String, streaming: Boolean, clip: android.content.ClipboardManager) {
    val segs = remember(content) { parseSegments(content) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segs.forEach { seg ->
            when (seg) {
                is Seg.Txt  -> if (seg.t.isNotBlank()) {
                    Text(seg.t.trim(), color = C_TEXT, fontSize = 14.sp, lineHeight = 23.sp)
                }
                is Seg.Code -> CodeBlock(seg.lang, seg.code, clip)
            }
        }
        if (streaming) {
            val a by rememberInfiniteTransition(label = "").animateFloat(
                0f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), ""
            )
            Box(Modifier.size(8.dp, 16.dp).alpha(a).background(C_BLUE, RoundedCornerShape(2.dp)))
        }
    }
}

@Composable
fun TypingDots() {
    val inf = rememberInfiniteTransition(label = "")
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by inf.animateFloat(
                .15f, 1f,
                infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse), ""
            )
            Box(Modifier.size(9.dp).alpha(a).background(C_BLUE, CircleShape))
        }
    }
}

// ════════════════════════════════════════════
//  CODE BLOCK
// ════════════════════════════════════════════
@Composable
fun CodeBlock(lang: String, code: String, clip: android.content.ClipboardManager) {
    var copied by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(copied) { if (copied) { delay(2000); copied = false } }

    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF050508), border = BorderStroke(1.dp, C_BORDER)) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(C_SURF).padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(Color(0xFFFF5F56), CircleShape))
                    Box(Modifier.size(8.dp).background(Color(0xFFFFBD2E), CircleShape))
                    Box(Modifier.size(8.dp).background(Color(0xFF27C93F), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(lang.ifEmpty { "code" }, fontSize = 11.sp, color = C_MUTED, fontFamily = FontFamily.Monospace)
                }
                TextButton(
                    onClick = { clip.setPrimaryClip(android.content.ClipData.newPlainText("code", code)); copied = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(
                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        null, Modifier.size(13.dp), if (copied) C_GREEN else C_MUTED
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (copied) "✓ تم النسخ" else "نسخ", fontSize = 11.sp, color = if (copied) C_GREEN else C_MUTED)
                }
            }
            Box(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(14.dp)) {
                Text(code, color = Color(0xFFABB2BF), fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 21.sp)
            }
        }
    }
}

// ════════════════════════════════════════════
//  SEGMENT PARSING
// ════════════════════════════════════════════
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

// ════════════════════════════════════════════
//  HISTORY SCREEN
// ════════════════════════════════════════════
@Composable
fun HistoryScreen(convs: List<Conversation>, onOpen: (Conversation) -> Unit, onDelete: (String) -> Unit, onNew: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM • HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().background(C_BG)) {
        // Header
        Surface(color = C_SURF) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("سجل المحادثات", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C_TEXT, modifier = Modifier.weight(1f))
                FilledIconButton(onClick = onNew, colors = IconButtonDefaults.filledIconButtonColors(containerColor = C_BLUE)) {
                    Icon(Icons.Default.Add, null, tint = Color.White)
                }
            }
        }

        if (convs.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("💬", fontSize = 48.sp)
                    Text("لا توجد محادثات سابقة", color = C_MUTED, fontSize = 14.sp)
                    Text("ابدأ محادثة جديدة من تبويب المحادثة", color = C_MUTED, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(convs, key = { it.id }) { conv ->
                    ConvCard(conv, fmt, { onOpen(conv) }, { onDelete(conv.id) })
                }
            }
        }
    }
}

@Composable
fun ConvCard(conv: Conversation, fmt: SimpleDateFormat, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(
        shape  = RoundedCornerShape(14.dp),
        color  = C_CARD,
        border = BorderStroke(1.dp, C_BORDER),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onDelete)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).background(conv.mode.color.copy(.15f), RoundedCornerShape(10.dp))
                    .border(1.dp, conv.mode.color.copy(.3f), RoundedCornerShape(10.dp)),
                Alignment.Center
            ) { Text(conv.mode.emoji, fontSize = 20.sp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(conv.title, color = C_TEXT, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${conv.messages.size} رسالة  •  ${fmt.format(Date(conv.ts))}", color = C_MUTED, fontSize = 11.sp)
                Text(conv.mode.label, color = conv.mode.color, fontSize = 11.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = C_MUTED, modifier = Modifier.size(18.dp))
        }
    }
}

// ════════════════════════════════════════════
//  SETTINGS SCREEN
// ════════════════════════════════════════════
@Composable
fun SettingsScreen() {
    Column(Modifier.fillMaxSize().background(C_BG)) {
        Surface(color = C_SURF) {
            Text(
                "الإعدادات والمعلومات",
                fontWeight = FontWeight.Bold, fontSize = 18.sp, color = C_TEXT,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp, 12.dp)
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // About card
            InfoCard("🤖", "الذراع البرمجي", "نسخة 2.0 — مدعوم بـ Gemini 2.5 Pro مع تفكير عميق وبحث Google. يعمل على 5 مفاتيح AI متناوبة لضمان الاستمرارية.")
            InfoCard("⚡", "التفكير العميق", "يستخدم حتى 16,000 token للتفكير الداخلي قبل الإجابة — مما يعطي نتائج أدق بكثير.")
            InfoCard("🌐", "بحث الإنترنت", "يبحث في Google تلقائياً عند الحاجة للمعلومات الحديثة أو توثيق المكتبات.")
            InfoCard("💾", "حفظ المحادثات", "تُحفظ جميع محادثاتك محلياً على الجهاز — خصوصية تامة بدون سيرفر.")

            // Server info
            Surface(shape = RoundedCornerShape(14.dp), color = C_CARD, border = BorderStroke(1.dp, C_BORDER)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🔌 معلومات الاتصال", color = C_BLUE, fontWeight = FontWeight.Bold)
                    Text("السيرفر: ${ApiClient.BASE_URL.take(40)}...", color = C_MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    Text("النموذج الرئيسي: gemini-2.5-pro-preview", color = C_MUTED, fontSize = 12.sp)
                    Text("النسخ الاحتياطية: 5 نماذج متسلسلة", color = C_MUTED, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun InfoCard(emoji: String, title: String, desc: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = C_CARD, border = BorderStroke(1.dp, C_BORDER)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(emoji, fontSize = 22.sp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = C_TEXT, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(desc, color = C_MUTED, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}
