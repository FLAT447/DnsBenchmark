package com.example.dnsbenchmark

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random
import kotlin.system.measureTimeMillis

enum class SaveFormat { TXT, CSV, MARKDOWN }
enum class AppTheme { LIGHT, DARK, AUTO }
enum class AppScreen { MAIN, RESULTS }

data class ResultFilter(val exactReplies: Int = -1)
enum class ResultSort { LATENCY, STABILITY }

data class DnsResult(
    val ip: String,
    val latency: Long,
    val requestsSent: Int,
    val successfulReplies: Int,
    val error: String?
) {
    val stabilityString: String
        get() = "$successfulReplies/$requestsSent"
}

data class AppSettings(
    val theme: AppTheme = AppTheme.AUTO,
    val saveFormat: SaveFormat = SaveFormat.TXT,
    val pingCount: Int = 6,
    val timeoutMs: Int = 400,
    val maxConcurrent: Int = 30,
    val delayBetweenPings: Int = 25
)

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("dns_benchmark_prefs", Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings {
        val themeStr = prefs.getString("theme", AppTheme.AUTO.name) ?: AppTheme.AUTO.name
        val formatStr = prefs.getString("save_format", SaveFormat.TXT.name) ?: SaveFormat.TXT.name
        return AppSettings(
            theme = try { AppTheme.valueOf(themeStr) } catch (e: Exception) { AppTheme.AUTO },
            saveFormat = try { SaveFormat.valueOf(formatStr) } catch (e: Exception) { SaveFormat.TXT },
            pingCount = prefs.getInt("ping_count", 3).coerceIn(1, 10),
            timeoutMs = prefs.getInt("timeout_ms", 400).coerceIn(100, 2000),
            maxConcurrent = prefs.getInt("max_concurrent", 30).coerceIn(5, 100),
            delayBetweenPings = prefs.getInt("delay_between_pings", 25).coerceIn(0, 500)
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString("theme", settings.theme.name)
            .putString("save_format", settings.saveFormat.name)
            .putInt("ping_count", settings.pingCount)
            .putInt("timeout_ms", settings.timeoutMs)
            .putInt("max_concurrent", settings.maxConcurrent)
            .putInt("delay_between_pings", settings.delayBetweenPings)
            .apply()
    }
}

private val IP_REGEX = Regex(
    """\b(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b"""
)

fun parseIpList(text: String): List<String> {
    if (text.isBlank()) return emptyList()
    return IP_REGEX.findAll(text).map { it.value }.distinct().toList()
}

private val DNS_QUERY_BODY = byteArrayOf(
    0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x06, 0x67, 0x6f, 0x6f,
    0x67, 0x6c, 0x65, 0x03, 0x63, 0x6f, 0x6d, 0x00,
    0x00, 0x01, 0x00, 0x01
)

class DnsTester {

    suspend fun testSingleServer(
        ip: String,
        pingCount: Int = 3,
        timeoutMs: Int = 400,
        delayBetweenPings: Int = 25
    ): DnsResult = withContext(Dispatchers.IO) {
        var successfulReplies = 0
        var totalLatency = 0L
        var lastError: String? = null

        try {
            val address = withTimeoutOrNull(timeoutMs.toLong()) {
                withContext(Dispatchers.IO) { InetAddress.getByName(ip) }
            } ?: return@withContext DnsResult(ip, Long.MAX_VALUE, pingCount, 0, "Host resolve timeout")

            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                try { socket.receiveBufferSize = 65536 } catch (_: Exception) {}

                for (i in 0 until pingCount) {
                    if (!isActive) break

                    try {
                        val txIdHi = Random.nextInt(256).toByte()
                        val txIdLo = Random.nextInt(256).toByte()

                        val dnsQuery = byteArrayOf(txIdHi, txIdLo) + DNS_QUERY_BODY
                        val sendPacket = DatagramPacket(dnsQuery, dnsQuery.size, address, 53)

                        val responseBuffer = ByteArray(512)
                        val receivePacket = DatagramPacket(responseBuffer, responseBuffer.size)

                        val latency = measureTimeMillis {
                            socket.send(sendPacket)

                            val startTime = System.currentTimeMillis()
                            var validResponse = false
                            while (!validResponse) {
                                val elapsed = System.currentTimeMillis() - startTime
                                val remaining = (timeoutMs - elapsed).toInt()
                                if (remaining <= 0) throw java.net.SocketTimeoutException("Timeout")
                                socket.soTimeout = remaining
                                socket.receive(receivePacket)
                                if (responseBuffer[0] == txIdHi && responseBuffer[1] == txIdLo) {
                                    validResponse = true
                                }
                            }
                        }

                        totalLatency += latency
                        successfulReplies++

                    } catch (e: Exception) {
                        lastError = e.localizedMessage ?: "Timeout"
                    }

                    if (i < pingCount - 1 && delayBetweenPings > 0) {
                        delay(delayBetweenPings.toLong())
                    }
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext DnsResult(ip, Long.MAX_VALUE, pingCount, 0, e.localizedMessage ?: "Host error")
        }

        val avgLatency = if (successfulReplies > 0) totalLatency / successfulReplies else Long.MAX_VALUE
        val errorMsg = if (successfulReplies == 0) lastError ?: "No replies" else null

        DnsResult(ip, avgLatency, pingCount, successfulReplies, errorMsg)
    }

    suspend fun testMultipleServersParallel(
        ips: List<String>,
        pingCount: Int = 3,
        timeoutMs: Int = 400,
        delayBetweenPings: Int = 25,
        maxConcurrent: Int = 30,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): List<DnsResult> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(maxConcurrent)
        var completed = 0

        val tasks = ips.map { ip ->
            async {
                semaphore.withPermit {
                    val result = testSingleServer(ip, pingCount, timeoutMs, delayBetweenPings)
                    synchronized(this) { completed++ }
                    onProgress(completed, ips.size)
                    result
                }
            }
        }
        tasks.awaitAll()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(applicationContext)

        setContent {
            var settingsState by remember { mutableStateOf<AppSettings?>(null) }

            LaunchedEffect(Unit) {
                settingsState = withContext(Dispatchers.IO) { settingsManager.loadSettings() }
            }

            val currentSettings = settingsState ?: AppSettings()
            val isDarkTheme = when (currentSettings.theme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.AUTO -> isSystemInDarkTheme()
            }

            MaterialTheme(colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settingsState == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        val mutableSettings = remember(currentSettings) { mutableStateOf(currentSettings) }
                        DnsBenchmarkScreen(
                            settings = mutableSettings,
                            onSettingsChanged = { newSettings ->
                                settingsState = newSettings
                                settingsManager.saveSettings(newSettings)
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsBenchmarkScreen(
    settings: State<AppSettings>,
    onSettingsChanged: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dnsTester = remember { DnsTester() }

    var currentScreen by remember { mutableStateOf(AppScreen.MAIN) }
    var inputText by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<DnsResult>>(emptyList()) }
    var statusMessage by remember { mutableStateOf("Готов к работе") }
    var resultsStatusMessage by remember { mutableStateOf("") }
    var savedFilePath by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }

    var currentFilter by remember { mutableStateOf(ResultFilter(-1)) }
    var currentSort by remember { mutableStateOf(ResultSort.LATENCY) }

    var testJob by remember { mutableStateOf<Job?>(null) }

    var hasPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasPermission = isGranted
            if (!isGranted) statusMessage = "❌ Нужен доступ к памяти!"
        }
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                scope.launch(Dispatchers.Default) {
                    try {
                        withContext(Dispatchers.Main) { statusMessage = "🔄 Чтение файла..." }
                        val fileContent = readFileFromUri(context, uri)
                        val ips = parseIpList(fileContent)
                        withContext(Dispatchers.Main) {
                            if (ips.isNotEmpty()) {
                                inputText = ips.joinToString("\n")
                                statusMessage = "✅ Загружено ${ips.size} IP"
                            } else {
                                statusMessage = "❌ Нет корректных IP в файле"
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusMessage = "❌ Ошибка: ${e.localizedMessage}"
                        }
                    }
                }
            }
        }
    )

    if (currentScreen == AppScreen.RESULTS) {
        BackHandler { currentScreen = AppScreen.MAIN }
    }

    fun startTest() {
        testJob = scope.launch {
            isTesting = true
            savedFilePath = ""
            resultsStatusMessage = ""
            currentFilter = ResultFilter(-1)
            currentScreen = AppScreen.RESULTS

            val ipList = withContext(Dispatchers.Default) { parseIpList(inputText) }
            val finalIpList = ipList.ifEmpty { listOf("8.8.8.8", "1.1.1.1", "77.88.8.8") }

            progress = 0 to finalIpList.size
            statusMessage = "🔄 Проверка ${finalIpList.size} серверов..."
            resultsStatusMessage = "🔄 Проверка ${finalIpList.size} серверов (пингов: ${settings.value.pingCount})..."

            val tempResults = dnsTester.testMultipleServersParallel(
                ips = finalIpList,
                pingCount = settings.value.pingCount,
                timeoutMs = settings.value.timeoutMs,
                delayBetweenPings = settings.value.delayBetweenPings,
                maxConcurrent = settings.value.maxConcurrent,
                onProgress = { completed, total ->
                    scope.launch(Dispatchers.Main) { progress = completed to total }
                }
            )

            results = tempResults
            isTesting = false

            val working = results.count { it.successfulReplies > 0 }
            val replyCount = "✅ Ответили: $working/${finalIpList.size}"

            if (working > 0) {
                try {
                    val path = withContext(Dispatchers.IO) {
                        saveToExternalStorage(
                            context,
                            results.filter { it.successfulReplies > 0 }.sortedBy { it.latency },
                            settings.value.saveFormat
                        )
                    }
                    savedFilePath = path
                    statusMessage = "$replyCount\n💾 Сохранено: $path"
                } catch (e: Exception) {
                    statusMessage = "$replyCount\n❌ Ошибка сохранения: ${e.localizedMessage}"
                }
            } else {
                statusMessage = replyCount
            }
            resultsStatusMessage = replyCount
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (currentScreen == AppScreen.MAIN) "DNS Benchmark" else "Результаты")
                },
                navigationIcon = {
                    if (currentScreen == AppScreen.RESULTS) {
                        IconButton(onClick = { currentScreen = AppScreen.MAIN }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (currentScreen == AppScreen.MAIN) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Настройки")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            when (currentScreen) {
                AppScreen.MAIN -> MainScreen(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    isTesting = isTesting,
                    hasPermission = hasPermission,
                    hasResults = results.isNotEmpty(),
                    statusMessage = statusMessage,
                    resultsCount = results.size,
                    onImportClick = { filePickerLauncher.launch("*/*") },
                    onStartClick = {
                        if (!hasPermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        } else {
                            startTest()
                        }
                    },
                    onOpenResultsClick = { currentScreen = AppScreen.RESULTS }
                )

                AppScreen.RESULTS -> ResultsScreen(
                    results = results,
                    isTesting = isTesting,
                    statusMessage = resultsStatusMessage,
                    currentFilter = currentFilter,
                    currentSort = currentSort,
                    settings = settings,
                    progress = progress,
                    onFilterChange = { currentFilter = it },
                    onSortChange = { currentSort = it }
                )
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onSettingsChanged = onSettingsChanged,
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
fun MainScreen(
    inputText: String,
    onInputChange: (String) -> Unit,
    isTesting: Boolean,
    hasPermission: Boolean,
    hasResults: Boolean,
    statusMessage: String,
    resultsCount: Int,
    onImportClick: () -> Unit,
    onStartClick: () -> Unit,
    onOpenResultsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChange,
            label = { Text("Список IP (каждый с новой строки)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            enabled = !isTesting
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onImportClick,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTesting
        ) {
            Text("Импорт списка из файла")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onStartClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTesting
        ) {
            val needsPerm = !hasPermission && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
            Text(if (needsPerm) "Выдать доступ к памяти" else "Запустить тест")
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onOpenResultsClick,
            enabled = hasResults && !isTesting,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(if (hasResults) "Результаты ($resultsCount)" else "Результаты отсутствуют")
                if (hasResults) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Статус:", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ResultsScreen(
    results: List<DnsResult>,
    isTesting: Boolean,
    statusMessage: String,
    currentFilter: ResultFilter,
    currentSort: ResultSort,
    settings: State<AppSettings>,
    progress: Pair<Int, Int>,
    onFilterChange: (ResultFilter) -> Unit,
    onSortChange: (ResultSort) -> Unit
) {
    val filteredAndSorted = remember(results, currentFilter, currentSort) {
        val filtered = if (currentFilter.exactReplies == -1) results
        else results.filter { it.successfulReplies == currentFilter.exactReplies }

        when (currentSort) {
            ResultSort.LATENCY -> filtered.sortedBy { it.latency }
            ResultSort.STABILITY -> filtered.sortedWith(
                compareByDescending<DnsResult> { it.successfulReplies }.thenBy { it.latency }
            )
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        if (isTesting) {
            val (completed, total) = progress
            val progressFraction = if (total > 0) completed.toFloat() / total else 0f

            Text(
                "Проверка: $completed / $total",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { progressFraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                var showFilterMenu by remember { mutableStateOf(false) }
                var showSortMenu by remember { mutableStateOf(false) }

                Box {
                    OutlinedButton(
                        onClick = { showFilterMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (currentFilter.exactReplies == -1) "Все ответы"
                            else "${currentFilter.exactReplies} из ${settings.value.pingCount}"
                        )
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Показать все") },
                            onClick = { onFilterChange(ResultFilter(-1)); showFilterMenu = false }
                        )
                        for (i in settings.value.pingCount downTo 0) {
                            DropdownMenuItem(
                                text = { Text("$i из ${settings.value.pingCount}") },
                                onClick = { onFilterChange(ResultFilter(i)); showFilterMenu = false }
                            )
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { showSortMenu = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(if (currentSort == ResultSort.LATENCY) "По задержке" else "По стабильности")
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("По задержке (быстрые выше)") },
                            onClick = { onSortChange(ResultSort.LATENCY); showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("По стабильности") },
                            onClick = { onSortChange(ResultSort.STABILITY); showSortMenu = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        statusMessage,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Показано: ${filteredAndSorted.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (filteredAndSorted.isNotEmpty()) {
                    val ctx = LocalContext.current
                    OutlinedButton(
                        onClick = {
                            val ips = filteredAndSorted.joinToString("\n") { it.ip }
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("dns_list", ips))
                            Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text("Копировать")
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(filteredAndSorted, key = { it.ip }) { res ->
                DnsResultCard(res)
            }
        }
    }
}

@Composable
fun DnsResultCard(res: DnsResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(res.ip, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Успешно: ${res.stabilityString}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (res.error != null && res.successfulReplies == 0) {
                    Text(
                        res.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (res.successfulReplies == 0) {
                Text("❌ Ошибка", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${res.latency} мс",
                        color = when {
                            res.latency < 50 -> MaterialTheme.colorScheme.primary
                            res.latency < 150 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    settings: State<AppSettings>,
    onSettingsChanged: (AppSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text("Тема", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                AppTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSettingsChanged(settings.value.copy(theme = theme)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.value.theme == theme,
                            onClick = { onSettingsChanged(settings.value.copy(theme = theme)) }
                        )
                        Text(
                            when (theme) {
                                AppTheme.LIGHT -> "Светлая"
                                AppTheme.DARK -> "Тёмная"
                                AppTheme.AUTO -> "Авто"
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Формат сохранения", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                SaveFormat.entries.forEach { format ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSettingsChanged(settings.value.copy(saveFormat = format)) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settings.value.saveFormat == format,
                            onClick = { onSettingsChanged(settings.value.copy(saveFormat = format)) }
                        )
                        Text(format.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "Пакетов на IP: ${settings.value.pingCount}",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = settings.value.pingCount.toFloat(),
                    onValueChange = { onSettingsChanged(settings.value.copy(pingCount = it.toInt())) },
                    valueRange = 1f..10f,
                    steps = 8
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Таймаут ответа: ${settings.value.timeoutMs} мс",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = settings.value.timeoutMs.toFloat(),
                    onValueChange = { onSettingsChanged(settings.value.copy(timeoutMs = it.toInt())) },
                    valueRange = 100f..2000f,
                    steps = 18
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Параллельных запросов: ${settings.value.maxConcurrent}",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = settings.value.maxConcurrent.toFloat(),
                    onValueChange = { onSettingsChanged(settings.value.copy(maxConcurrent = it.toInt())) },
                    valueRange = 5f..100f,
                    steps = 18
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Задержка между пингами: ${settings.value.delayBetweenPings} мс",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = settings.value.delayBetweenPings.toFloat(),
                    onValueChange = { onSettingsChanged(settings.value.copy(delayBetweenPings = it.toInt())) },
                    valueRange = 0f..500f,
                    steps = 24
                )

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Text("Об авторе", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/flat447"))
                            ctx.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Telegram",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Telegram")
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/FLAT447"))
                            ctx.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "GitHub",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("GitHub")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Готово") }
        }
    )
}

fun saveToExternalStorage(context: Context, results: List<DnsResult>, format: SaveFormat): String {
    val validResults = results.filter { it.successfulReplies > 0 }

    val (fileName, content) = when (format) {
        SaveFormat.TXT -> "working_dns.txt" to
                validResults.joinToString("\n") { it.ip }

        SaveFormat.CSV -> "working_dns.csv" to
                ("IP,Latency (ms),Stability\n" +
                        validResults.joinToString("\n") { "${it.ip},${it.latency},${it.stabilityString}" })

        SaveFormat.MARKDOWN -> "working_dns.md" to
                ("# Рабочие DNS Серверы\n\n| IP | Задержка | Стабильность |\n|---|---|---|\n" +
                        validResults.joinToString("\n") { "| ${it.ip} | ${it.latency} мс | ${it.stabilityString} |" })
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()
            val file = File(downloadDir, fileName)
            file.writeText(content)
            file.absolutePath
        } catch (e: Exception) {
            val cacheFile = File(context.cacheDir, fileName)
            cacheFile.writeText(content)
            cacheFile.absolutePath
        }
    } else {
        val rootDir = Environment.getExternalStorageDirectory()
        if (!rootDir.exists()) rootDir.mkdirs()
        val file = File(rootDir, fileName)
        file.writeText(content)
        file.absolutePath
    }
}

fun readFileFromUri(context: Context, uri: Uri): String =
    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        ?: throw Exception("Не удалось открыть файл")