package com.swiftproxy.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                val url = URL(address)
                require(url.protocol.equals("https", ignoreCase = true)) {
                    "为保护订阅信息，目前只允许 HTTPS 地址"
                }
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "SwiftProxy-Android")
                }
                try {
                    val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val nodes = NodeParser.parseSubscription(text)
                    if (nodes.isEmpty()) error("没有识别到 VLESS、VMess、SS 或 Trojan 节点")
                    runOnUiThread { callback(Result.success(nodes)) }
                } finally {
                    connection.disconnect()
                }
            } catch (error: Exception) {
                runOnUiThread { callback(Result.failure(error)) }
            }
        }.start()
    }
}

@Composable
private fun SwiftProxyHome(
    loadNodes: () -> List<ProxyNode>,
    saveNodes: (List<ProxyNode>) -> Unit,
    fetchSubscription: (String, (Result<List<ProxyNode>>) -> Unit) -> Unit
) {
    val nodes = remember { mutableStateListOf<ProxyNode>() }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSubscriptionDialog by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }
    var subscriptionInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("代理核心尚未接入，当前只能管理节点") }
    var importing by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        nodes.clear()
        nodes.addAll(loadNodes())
        selectedId = nodes.firstOrNull()?.id.orEmpty()
    }

    fun persist() = saveNodes(nodes.toList())

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
                Text("0.4.0 · 节点管理", color = Muted, fontSize = 12.sp)
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
                        Box(
                            modifier = Modifier.background(Color(0xFF263448), CircleShape).padding(10.dp)
                        ) {
                            Text("OFF", color = Accent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.padding(6.dp))
                        Column {
                            Text("尚未连接", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("代理核心正在开发中", color = Muted, fontSize = 12.sp)
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

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
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
            }

            Spacer(Modifier.height(12.dp))
            Text(status, color = Muted, fontSize = 12.sp)
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
                                    Text("${node.type}  ·  ${node.server.ifBlank { "服务器地址待解析" }}", color = Muted, fontSize = 11.sp)
                                }
                                TextButton(onClick = {
                                    nodes.remove(node)
                                    if (selectedId == node.id) selectedId = nodes.firstOrNull()?.id.orEmpty()
                                    persist()
                                }) { Text("删除", color = Color(0xFFFF8A8A)) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = { status = "节点已保存；连接功能需要等待 sing-box 核心接入" },
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(disabledContainerColor = Color(0xFF263448), disabledContentColor = Muted)
            ) { Text("连接功能开发中") }
            Spacer(Modifier.height(8.dp))
            Text("节点信息仅保存在本机应用内", color = Muted, fontSize = 11.sp)
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
                            val existing = nodes.map { it.rawUri }.toSet()
                            val fresh = imported.filter { it.rawUri !in existing }
                            nodes.addAll(fresh)
                            selectedId = nodes.firstOrNull()?.id.orEmpty()
                            persist()
                            status = "订阅解析完成：识别 ${imported.size} 个节点，新增 ${fresh.size} 个"
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
