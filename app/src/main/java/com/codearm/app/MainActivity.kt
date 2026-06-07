package com.codearm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.*
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
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CodeArmApp() }
    }
}

val DarkBackground = Color(0xFF0D1117)
val DarkSurface   = Color(0xFF161B22)
val DarkCard      = Color(0xFF21262D)
val PrimaryBlue   = Color(0xFF2F81F7)
val AccentGreen   = Color(0xFF3FB950)
val AccentOrange  = Color(0xFFF78166)
val TextPrimary   = Color(0xFFE6EDF3)
val TextSecondary = Color(0xFF8B949E)

enum class Tool(val label: String, val icon: @Composable () -> Unit) {
    HASH("هاش SHA-256", { Icon(Icons.Default.Lock, contentDescription = null, tint = PrimaryBlue) }),
    BASE64("Base64", { Icon(Icons.Default.Code, contentDescription = null, tint = AccentGreen) }),
    PASS("كلمة مرور", { Icon(Icons.Default.Security, contentDescription = null, tint = AccentOrange) }),
    ASCII("ASCII / Unicode", { Icon(Icons.Default.Translate, contentDescription = null, tint = Color(0xFFD2A8FF)) }),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeArmApp() {
    var selectedTool by remember { mutableStateOf(Tool.HASH) }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = DarkBackground,
            surface = DarkSurface,
            primary = PrimaryBlue,
            onPrimary = Color.White,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
        )
    ) {
        Scaffold(
            containerColor = DarkBackground,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "الذراع البرمجي",
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = TextPrimary
                            )
                            Text(
                                "أدوات المطور",
                                fontSize = 12.sp,
                                color = PrimaryBlue
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = DarkSurface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(DarkBackground)
            ) {
                // Tool selector
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(Tool.values()) { tool ->
                        val selected = tool == selectedTool
                        Surface(
                            onClick = { selectedTool = tool },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selected) PrimaryBlue else DarkCard,
                            border = if (!selected) BorderStroke(1.dp, Color(0xFF30363D)) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                tool.icon()
                                Text(
                                    tool.label,
                                    fontSize = 13.sp,
                                    color = if (selected) Color.White else TextSecondary
                                )
                            }
                        }
                    }
                }

                // Tool content
                AnimatedContent(targetState = selectedTool) { tool ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        when (tool) {
                            Tool.HASH -> HashTool()
                            Tool.BASE64 -> Base64Tool()
                            Tool.PASS -> PasswordTool()
                            Tool.ASCII -> AsciiTool()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ToolCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = DarkCard,
        border = BorderStroke(1.dp, Color(0xFF30363D))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = PrimaryBlue, fontSize = 14.sp)
            content()
        }
    }
}

@Composable
fun OutputBox(label: String, value: String) {
    val ctx = LocalContext.current
    val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 12.sp, color = TextSecondary)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = DarkBackground
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value.ifEmpty { "—" },
                    modifier = Modifier.weight(1f),
                    fontSize = 13.sp,
                    color = if (value.isEmpty()) TextSecondary else AccentGreen,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 4
                )
                if (value.isNotEmpty()) {
                    IconButton(onClick = {
                        val clip = android.content.ClipData.newPlainText("result", value)
                        clipboard.setPrimaryClip(clip)
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "نسخ", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HashTool() {
    var input by remember { mutableStateOf("") }
    val hash = remember(input) {
        if (input.isEmpty()) "" else {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
    val md5 = remember(input) {
        if (input.isEmpty()) "" else {
            val md = MessageDigest.getInstance("MD5")
            md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    ToolCard("🔐 مولّد الهاش") {
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("أدخل النص هنا...", color = TextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = Color(0xFF30363D),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = PrimaryBlue
            )
        )
        OutputBox("SHA-256", hash)
        OutputBox("MD5", md5)
    }
}

@Composable
fun Base64Tool() {
    var input by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(true) } // true = encode
    val result = remember(input, mode) {
        if (input.isEmpty()) return@remember ""
        try {
            if (mode) Base64.getEncoder().encodeToString(input.toByteArray())
            else String(Base64.getDecoder().decode(input))
        } catch (e: Exception) { "خطأ في الإدخال" }
    }

    ToolCard("📦 Base64") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("تشفير" to true, "فك التشفير" to false).forEach { (label, v) ->
                FilterChip(
                    selected = mode == v, onClick = { mode = v },
                    label = { Text(label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryBlue,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("أدخل النص...", color = TextSecondary) },
            minLines = 2,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = Color(0xFF30363D),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = PrimaryBlue
            )
        )
        OutputBox("النتيجة", result)
    }
}

@Composable
fun PasswordTool() {
    var length by remember { mutableStateOf(16f) }
    var useSymbols by remember { mutableStateOf(true) }
    var useNumbers by remember { mutableStateOf(true) }
    var useUpper by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }

    fun generate() {
        val chars = buildString {
            append("abcdefghijklmnopqrstuvwxyz")
            if (useUpper) append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (useNumbers) append("0123456789")
            if (useSymbols) append("!@#$%^&*()_+-=[]{}|;:,.<>?")
        }
        password = (1..length.toInt()).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    LaunchedEffect(Unit) { generate() }

    ToolCard("🔑 مولّد كلمة المرور") {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("الطول: ${length.toInt()}", color = TextPrimary, fontSize = 14.sp)
        }
        Slider(
            value = length, onValueChange = { length = it },
            valueRange = 8f..64f,
            colors = SliderDefaults.colors(thumbColor = PrimaryBlue, activeTrackColor = PrimaryBlue)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                "أحرف كبيرة" to useUpper to { v: Boolean -> useUpper = v },
                "أرقام" to useNumbers to { v: Boolean -> useNumbers = v },
                "رموز" to useSymbols to { v: Boolean -> useSymbols = v },
            ).forEach { (pair, action) ->
                val (label, state) = pair
                FilterChip(
                    selected = state, onClick = { action(!state) },
                    label = { Text(label, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = PrimaryBlue,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }
        OutputBox("كلمة المرور", password)
        Button(
            onClick = { generate() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("توليد جديد")
        }
    }
}

@Composable
fun AsciiTool() {
    var input by remember { mutableStateOf("") }
    val ascii = remember(input) {
        input.map { "${it} → ${it.code}" }.joinToString("\n")
    }
    val unicode = remember(input) {
        input.map { "U+%04X".format(it.code) }.joinToString(" ")
    }

    ToolCard("🔤 ASCII / Unicode") {
        OutlinedTextField(
            value = input, onValueChange = { if (it.length <= 50) input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("اكتب أحرف أو رموز...", color = TextSecondary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = Color(0xFF30363D),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = PrimaryBlue
            )
        )
        OutputBox("قيم ASCII", ascii)
        OutputBox("Unicode", unicode)
    }
}
