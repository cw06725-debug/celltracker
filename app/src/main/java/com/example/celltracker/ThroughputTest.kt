package com.example.celltracker

import android.content.Intent
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Measures REAL HTTP download payload transferred to this handset (not simulated link capacity).
 * This first cut intentionally does not claim to measure cellular-only throughput or uplink.
 */
internal data class HttpThroughputSample(val time: Long, val bytes: Long, val elapsedNs: Long, val url: String) {
    val mbps: Double get() = bytes * 8.0 * 1000.0 / elapsedNs.coerceAtLeast(1L)
}

internal object HttpThroughputEngine {
    suspend fun download(url: String): HttpThroughputSample = withContext(Dispatchers.IO) {
        require(url.startsWith("https://", true)) { "HTTPS URL required" }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("Cache-Control", "no-cache")
            useCaches = false
        }
        try {
            val status = conn.responseCode
            require(status in 200..299) { "Server HTTP $status" }
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            val start = System.nanoTime()
            conn.inputStream.use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    check(total <= 100L * 1024 * 1024) { "Exceeded safety data cap (100 MiB)" }
                }
            }
            val elapsedNs = System.nanoTime() - start
            check(total >= 128 * 1024L) { "Response too small for meaningful speed test ($total bytes)" }
            HttpThroughputSample(System.currentTimeMillis(), total, elapsedNs, url)
        } finally { conn.disconnect() }
    }

    fun saveCsv(context: android.content.Context, label: String, samples: List<HttpThroughputSample>): File {
        require(samples.isNotEmpty()) { "No completed samples" }
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir,
            "Throughput").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = label.replace(Regex("[^A-Za-z0-9_-]"), "_").take(50).ifBlank { "Throughput" }
        return File(directory, "${safeName}_${stamp}.csv").apply {
            writeText(buildString {
                appendLine("Test,HTTP Download (real measured traffic)")
                appendLine("Task,${safeName}")
                appendLine("Samples,${samples.size}")
                appendLine("Average Mbps,${String.format(Locale.US, "%.2f", samples.map { it.mbps }.average())}")
                appendLine("Timestamp UTC ms,Bytes,Elapsed ms,Throughput Mbps,URL")
                samples.forEach { v -> appendLine("${v.time},${v.bytes},${v.elapsedNs / 1_000_000},${String.format(Locale.US,"%.2f",v.mbps)},${v.url}") }
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThroughputScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showSetup by remember { mutableStateOf(true) }
    var task by remember { mutableStateOf("Throughput") }
    var url by remember { mutableStateOf("https://speed.cloudflare.com/__down?bytes=10000000") }
    var number by remember { mutableStateOf("3") }
    var isRunning by remember { mutableStateOf(false) }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var message by remember { mutableStateOf("Ready") }
    var output by remember { mutableStateOf<File?>(null) }
    val samples = remember { mutableStateListOf<HttpThroughputSample>() }
    Scaffold(topBar = { TopAppBar(title = { Text("Throughput · HTTP DL") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Measures actual HTTP download Mbps. Results depend on server, Wi-Fi/cellular routing and congestion; it does not isolate radio throughput.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showSetup = true }, enabled = !isRunning) { Text("Test Setup") }
                if (isRunning) OutlinedButton(onClick = { runningJob?.cancel() }) { Text("STOP") }
            }
            Text("Status: $message")
            Text("Completed: ${samples.size}")
            samples.forEachIndexed { i, sample ->
                Text("Sample ${i + 1}: ${String.format(Locale.US, "%.2f Mbps", sample.mbps)} · ${sample.bytes} bytes")
            }
            if (samples.isNotEmpty()) {
                Text("Average: ${String.format(Locale.US, "%.2f Mbps", samples.map { it.mbps }.average())}",
                    style = MaterialTheme.typography.titleMedium)
            }
            output?.let { file ->
                OutlinedButton(onClick = {
                    runCatching {
                        val uri = FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"; putExtra(Intent.EXTRA_STREAM,uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },"Share throughput report"))
                    }.onFailure { message = "Share failed: ${it.message}" }
                }) { Text("Share original CSV") }
            }
        }
    }
    if (showSetup) {
        UnifiedTestSetupDialogV1270("Throughput Test", onDismiss = onBack) {
                    OutlinedTextField(task, {task=it.take(64)},label={Text("Task name")},singleLine=true)
                    OutlinedTextField(url, {url=it},label={Text("HTTPS download URL")})
                    OutlinedTextField(number,{number=it.filter(Char::isDigit).take(2)},label={Text("Sample count (1–10)")},singleLine=true)
                    Text("Each sample downloads up to 100 MiB (default 10 MB). Charges may apply. Wi-Fi and cellular routing are not controlled by this test.",
                        style=MaterialTheme.typography.bodySmall)
                Button(enabled = !isRunning && url.startsWith("https://") && (number.toIntOrNull() ?: 0) in 1..10,
                    onClick = {
                        val endpoint = url.trim()
                        val count = number.toIntOrNull() ?: 1
                        showSetup = false
                        samples.clear();output=null
                        runningJob = scope.launch {
                            isRunning = true
                            try {
                                for (i in 1..count) {
                                    message = "Downloading $i / $count…"
                                    samples.add(HttpThroughputEngine.download(endpoint))
                                }
                                output = HttpThroughputEngine.saveCsv(context, task, samples)
                                message = "Completed · CSV saved"
                            } catch (e: Exception) {
                                message = "Stopped: ${e.message} · ${samples.size} valid samples"
                                if(samples.isNotEmpty()) output = runCatching {
                                    HttpThroughputEngine.saveCsv(context,task,samples)
                                }.getOrNull()
                            } finally { isRunning = false; runningJob = null }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("START TEST") }
        }
    }
}
