package com.swiftproxy.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL

private val Background = Color(0xFF0B1020)
private val Panel = Color(0xFF151D31)
private val Accent = Color(0xFF70C8F0)
private val Muted = Color(0xFF9AA7BD)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Accent,
                background = Background,
                surface = Panel
            )) {
                SwiftProxyHome(
                    loadNodes = {
                        getSharedPreferences("swiftproxy", MODE_PRIVATE)
                            .getStringSet("nodes", emptySet()).orEmpty()
                            .mapNotNull(NodeParser::parseUri)
                    },
                    saveNodes = { nodes ->
                        getSharedPreferences("swiftproxy", MODE_PRIVATE).edit()
                            .putStringSet("nodes", nodes.map { it.rawUri }.toSet()).apply()
                    },
                    fetchSubscription = ::fetchSubscription
                )
            }
        }
    }

    private fun fetchSubscription(address: String, callback: (Result<List<ProxyNode>>) -> Unit) {
        Thread {
            try {
                val body = fetchHttpsSubscription(address)
                val nodes = NodeParser.parseSubscription(body)
                if (nodes.isEmpty()) error("没有识别到 VLESS、VMess、SS 或 Trojan 节点")
                runOnUiThread { callback(Result.success(nodes)) }
            } catch (error: Exception) {
                runOnUiThread { callback(Result.failure(error)) }
            }
        }.start()
    }

    private fun fetchHttpsSubscription(initialAddress: String): String {
        var current = URL(initialAddress)
        repeat(5) {
            require(current.protocol.equals("https", true)) { "订阅重定向必须保持 HTTPS" }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "SwiftProxy-Android")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: error("订阅重定向缺少目标地址")
                    current = URL(current, location)
                    return@repeat
                }
                check(code in 200..299) { "订阅服务器返回 HTTP $code" }
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        error("订阅重定向次数过多")
    }
}

@Composable
private fun SwiftProxyHome(
    loadNodes: () -> List<ProxyNode>,
    saveNodes: (List<ProxyNode>) -> Unit,
    fetchSubscription: (String, (Result<List<ProxyNode>>) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val nodes = remember { mutableStateListOf<ProxyNode>() }
    val latencyResults = remember { mutableStateMapOf<String, String>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }
    var subscriptionInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("选择节点后即可尝试建立 VPN 连接") }
    var importing by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf("") }
    var connectionState by remember { mutableStateOf("disconnected") }
    var rxBytes by remember { mutableStateOf(0L) }
    var txBytes by remember { mutableStateOf(0L) }
    var autoSwitch by remember { mutableStateOf(true) }
    var pendingConfig by remember { mutableStateOf<String?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val config = pendingConfig
        pendingConfig = null
        if (result.resultCode == Activity.RESULT_OK && config != null) {
            startVpnService(context, config)
        } else {
            status = "未获得系统 VPN 授权"
        }
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE).orEmpty().trim()
            if (value.startsWith("https://", true)) {
                subscriptionInput = value
                showSubscriptionDialog = true
                status = "已扫描订阅地址，请确认后导入"
            } else {
                val parsed = NodeParser.parseUri(value)
                if (parsed == null) {
                    status = "二维码内容不是支持的节点链接或 HTTPS 订阅地址"
                } else {
                    if (nodes.none { it.rawUri == parsed.rawUri }) nodes.add(parsed)
                    selectedId = parsed.id
                    saveNodes(nodes.toList())
                    status = "扫码添加成功：${parsed.name}"
                }
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    SwiftProxyVpnService.ACTION_STATS -> {
                        rxBytes = intent.getLongExtra("rx", 0L)
                        txBytes = intent.getLongExtra("tx", 0L)
                    }
                    SwiftProxyVpnService.ACTION_STATE -> {
                        connectionState = intent.getStringExtra("state") ?: "disconnected"
                        status = intent.getStringExtra("message") ?: "连接状态已更新"
                    }
                }
            }
        }
        val filter = IntentFilter(SwiftProxyVpnService.ACTION_STATE).apply {
            addAction(SwiftProxyVpnService.ACTION_STATS)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(Unit) {
        nodes.clear()
        nodes.addAll(loadNodes())
        selectedId = nodes.firstOrNull()?.id.orEmpty()
    }

    fun persist() = saveNodes(nodes.toList())

    fun requestConnect() {
        val node = nodes.firstOrNull { it.id == selectedId }
        if (node == null) {
            status = "请先选择一个节点"
            return
        }
        val config = runCatching { SingBoxConfigBuilder.buildForNodes(nodes.map { it.rawUri }, node.rawUri, autoSwitch) }.getOrElse {
            status = "节点配置生成失败：${it.message ?: "请检查分享链接"}"
            return
        }
        pendingConfig = config
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            vpnPermissionLauncher.launch(prepare)
        } else {
            pendingConfig = null
            startVpnService(context, config)
        }
    }

    fun requestDisconnect() {
        context.startService(Intent(context, SwiftProxyVpnService::class.java).setAction(SwiftProxyVpnService.ACTION_STOP))
        connectionState = "disconnected"
        status = "正在断开连接…"
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SwiftProxy", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("0.6.0 · sing-box", color = Muted, fontSize = 12.sp)
            }

            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("连接状态", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val active = connectionState == "connected" || connectionState == "starting"
                        Box(
                            modifier = Modifier.background(
                                if (active) Color(0xFF1D493F) else Color(0xFF263448),
                                CircleShape
                            ).padding(10.dp)
                        ) {
                            Text(
                                when (connectionState) {
                                    "connected" -> "ON"
                                    "starting" -> "…"
                                    else -> "OFF"
                                },
                                color = if (active) Color(0xFF80E0B4) else Accent,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.padding(6.dp))
                        Column {
                            Text(
                                when (connectionState) {
                                    "connected" -> "代理核心已启动"
                                    "starting" -> "正在连接"
                                    "error" -> "连接失败"
                                    else -> "尚未连接"
                                },
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(status, color = Muted, fontSize = 12.sp)
                            Text("应用网络流量  ↓ ${formatBytes(rxBytes)}   ↑ ${formatBytes(txBytes)}", color = Muted, fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("节点列表  ·  ${nodes.size}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showAddDialog = true }) { Text("+ 添加节点") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("自动选择低延迟节点", color = Color.White, fontSize = 13.sp)
                    Text("多个节点时由 sing-box 定期测速并切换", color = Muted, fontSize = 10.sp)
                }
                Switch(checked = autoSwitch, onCheckedChange = { autoSwitch = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("手动添加") }
                Button(
                    onClick = { showSubscriptionDialog = true },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("导入订阅") }
                OutlinedButton(
                    onClick = { qrScannerLauncher.launch(Intent(context, QrScannerActivity::class.java)) },
                    modifier = Modifier.weight(0.8f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("扫码") }
            }

            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.height(10.dp))

            if (nodes.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有节点", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("可粘贴单条分享链接，或导入 HTTPS 订阅地址", color = Muted, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(nodes, key = { it.id }) { node ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = if (node.id == selectedId) Color(0xFF1B3046) else Panel),
                            shape = RoundedCornerShape(16.dp),
                            onClick = { selectedId = node.id }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(node.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(4.dp))
                                    Text("${node.type}  ·  ${node.server}:${node.port}", color = Muted, fontSize = 11.sp)
                                    Text("TCP 延迟：${latencyResults[node.id] ?: "未测速"}", color = Muted, fontSize = 10.sp)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(onClick = {
                                        latencyResults[node.id] = "测速中…"
                                        NodeLatencyTester.test(node) { result -> latencyResults[node.id] = result }
                                    }) { Text("测速") }
                                    TextButton(onClick = {
                                    nodes.remove(node)
                                    if (selectedId == node.id) selectedId = nodes.firstOrNull()?.id.orEmpty()
                                    persist()
                                    latencyResults.remove(node.id)
                                }) { Text("删除", color = Color(0xFFFF8A8A)) }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    if (connectionState == "connected" || connectionState == "starting") requestDisconnect()
                    else requestConnect()
                },
                enabled = nodes.isNotEmpty() || connectionState == "connected" || connectionState == "starting",
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (connectionState == "connected") Color(0xFF9A4545) else Accent,
                    contentColor = if (connectionState == "connected") Color.White else Background,
                    disabledContainerColor = Color(0xFF263448),
                    disabledContentColor = Muted
                )
            ) {
                Text(if (connectionState == "connected" || connectionState == "starting") "断开连接" else "连接")
            }
            Spacer(Modifier.height(8.dp))
            Text("节点信息保存在本机；VPN 连接由 Android 系统授权", color = Muted, fontSize = 11.sp)
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("手动添加节点") },
            text = {
                Column {
                    Text("粘贴一条 VLESS、VMess、SS 或 Trojan 分享链接。", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = manualInput,
                        onValueChange = { manualInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("vless://...") },
                        minLines = 3,
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = NodeParser.parseUri(manualInput.trim())
                    if (parsed == null) {
                        status = "链接格式无法识别，请检查协议和完整链接"
                    } else {
                        if (nodes.none { it.rawUri == parsed.rawUri }) nodes.add(parsed)
                        selectedId = parsed.id
                        persist()
                        manualInput = ""
                        showAddDialog = false
                        status = "已添加节点：${parsed.name}"
                    }
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }

    if (showSubscriptionDialog) {
        AlertDialog(
            onDismissRequest = { if (!importing) showSubscriptionDialog = false },
            title = { Text("导入 HTTPS 订阅") },
            text = {
                Column {
                    Text("订阅地址只在本机请求，不要把地址发到聊天里。", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = subscriptionInput,
                        onValueChange = { subscriptionInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://订阅地址…") },
                        minLines = 2,
                        maxLines = 4,
                        enabled = !importing
                    )
                    if (importing) Text("正在获取并解析订阅…", color = Muted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = !importing, onClick = {
                    importing = true
                    status = "正在导入订阅…"
                    fetchSubscription(subscriptionInput.trim()) { result ->
                        importing = false
                        result.onSuccess { imported ->
                            val prefs = context.getSharedPreferences("swiftproxy", Context.MODE_PRIVATE)
                            val previousSubscriptionNodes = prefs.getStringSet("subscription_nodes", emptySet()).orEmpty().toSet()
                            val mergedUris = nodes.map { it.rawUri }.toMutableSet()
                            mergedUris.removeAll(previousSubscriptionNodes)
                            val importedUris = imported.map { it.rawUri }.toSet()
                            mergedUris.addAll(importedUris)
                            nodes.clear()
                            nodes.addAll(mergedUris.mapNotNull(NodeParser::parseUri))
                            selectedId = nodes.firstOrNull()?.id.orEmpty()
                            persist()
                            prefs.edit().putStringSet("subscription_nodes", importedUris).apply()
                            SecureStore.saveSubscriptionUrl(context, subscriptionInput.trim())
                            SubscriptionUpdateWorker.schedule(context)
                            status = "订阅导入完成：${imported.size} 个节点；已开启每 12 小时自动更新"
                            subscriptionInput = ""
                            showSubscriptionDialog = false
                        }.onFailure { error ->
                            status = "导入失败：${error.message ?: "请检查订阅地址"}"
                        }
                    }
                }) { Text("开始导入") }
            },
            dismissButton = { TextButton(enabled = !importing, onClick = { showSubscriptionDialog = false }) { Text("取消") } }
        )
    }
}

private fun startVpnService(context: Context, config: String) {
    val intent = Intent(context, SwiftProxyVpnService::class.java)
        .setAction(SwiftProxyVpnService.ACTION_START)
        .putExtra(SwiftProxyVpnService.EXTRA_CONFIG, config)
    ContextCompat.startForegroundService(context, intent)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.ROOT, "%.1f MB", mb)
    return String.format(java.util.Locale.ROOT, "%.2f GB", mb / 1024.0)
}
