package com.apkanalyzer.ui.screens

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.apkanalyzer.model.ApkInfo
import com.apkanalyzer.model.ComponentInfo
import com.apkanalyzer.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val apkInfo = viewModel.apkInfo
    val isAnalyzing = viewModel.isAnalyzing
    val error = viewModel.analyzeError

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                viewModel.analyzeApkFromUri(uri, context)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APK分析器") },
                actions = {
                    if (apkInfo != null) {
                        IconButton(onClick = { viewModel.clearApkInfo() }) {
                            Icon(Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/vnd.android.package-archive"
                    }
                    filePicker.launch(intent)
                }
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = "选择APK")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isAnalyzing -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Text("分析失败: $error", color = MaterialTheme.colorScheme.error)
                    }
                }
                apkInfo != null -> {
                    ApkInfoContent(apkInfo, viewModel)
                }
                else -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("点击右下角按钮选择APK文件")
                    }
                }
            }
        }
    }
}

@Composable
fun ApkInfoContent(apkInfo: ApkInfo, viewModel: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App header with icon
        item {
            Card {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (apkInfo.iconBytes != null) {
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                            apkInfo.iconBytes, 0, apkInfo.iconBytes.size
                        )
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "App Icon",
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        }
                    }
                    Column {
                        Text(
                            apkInfo.appLabel.ifEmpty { apkInfo.fileName },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(apkInfo.packageName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Basic info
        item {
            InfoCard(title = "基本信息") {
                InfoRow("文件名", apkInfo.fileName)
                InfoRow("文件大小", viewModel.analyzer.formatFileSize(apkInfo.fileSize))
                InfoRow("包名", apkInfo.packageName)
                InfoRow("版本", apkInfo.versionName)
                InfoRow("版本号", apkInfo.versionCode.toString())
                InfoRow("最低SDK", apkInfo.minSdkVersion.toString())
                InfoRow("目标SDK", apkInfo.targetSdkVersion.toString())
            }
        }

        // Hashes
        item {
            InfoCard(title = "哈希值") {
                InfoRow("MD5", apkInfo.md5, isMono = true)
                InfoRow("SHA1", apkInfo.sha1, isMono = true)
                InfoRow("SHA256", apkInfo.sha256, isMono = true)
            }
        }

        // Certificate
        apkInfo.certificateInfo?.let { cert ->
            item {
                InfoCard(title = "证书信息") {
                    InfoRow("主题", cert.subject)
                    InfoRow("颁发者", cert.issuer)
                    InfoRow("序列号", cert.serialNumber)
                    InfoRow("生效时间", cert.validFrom)
                    InfoRow("过期时间", cert.validUntil)
                    InfoRow("签名算法", cert.signatureAlgorithm)
                }
            }
        }

        // Permissions
        if (apkInfo.permissions.isNotEmpty()) {
            item {
                InfoCard(title = "权限 (${apkInfo.permissions.size})") {
                    apkInfo.permissions.forEach { perm ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(perm, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // Activities
        if (apkInfo.activities.isNotEmpty()) {
            item {
                ComponentCard("Activities", apkInfo.activities)
            }
        }

        // Services
        if (apkInfo.services.isNotEmpty()) {
            item {
                ComponentCard("Services", apkInfo.services)
            }
        }

        // Receivers
        if (apkInfo.receivers.isNotEmpty()) {
            item {
                ComponentCard("Receivers", apkInfo.receivers)
            }
        }

        // Providers
        if (apkInfo.providers.isNotEmpty()) {
            item {
                ComponentCard("Providers", apkInfo.providers)
            }
        }

        // Native libraries
        if (apkInfo.nativeLibraries.isNotEmpty()) {
            item {
                InfoCard(title = "原生库 (${apkInfo.nativeLibraries.size})") {
                    apkInfo.nativeLibraries.forEach { lib ->
                        Text(lib, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Resources
        if (apkInfo.resources.isNotEmpty()) {
            item {
                InfoCard(title = "资源文件 (${apkInfo.resources.size})") {
                    apkInfo.resources.take(20).forEach { res ->
                        Text("${res.name} (${viewModel.analyzer.formatFileSize(res.size)})",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (apkInfo.resources.size > 20) {
                        Text("... 还有 ${apkInfo.resources.size - 20} 个资源", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, isMono: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            value,
            style = if (isMono) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ComponentCard(title: String, components: List<ComponentInfo>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("$title (${components.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            components.forEach { comp ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (comp.exported) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (comp.exported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(comp.name, style = MaterialTheme.typography.bodySmall)
                        if (comp.exported) {
                            Text("exported", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
