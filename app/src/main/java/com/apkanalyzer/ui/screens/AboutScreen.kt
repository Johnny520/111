package com.apkanalyzer.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkanalyzer.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 应用图标和名称
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "APK Analyzer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "AI 智能逆向分析工具",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // 开发者信息
            InfoSection(
                icon = Icons.Default.Person,
                title = "开发者信息"
            ) {
                InfoRow("作者", "文强哥 (Johnny520)")
                ClickableInfoRow(
                    icon = Icons.Default.Language,
                    label = "官网",
                    value = "johnny520.github.io/Johnny/"
                ) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://johnny520.github.io/Johnny/"))
                    context.startActivity(intent)
                }
                ClickableInfoRow(
                    icon = GithubIcon,
                    label = "GitHub",
                    value = "github.com/Johnny520"
                ) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Johnny520"))
                    context.startActivity(intent)
                }
                InfoRow("邮箱", "1689969048@qq.com")
            }

            // 技术说明
            InfoSection(
                icon = Icons.Default.Code,
                title = "技术说明"
            ) {
                Text(
                    "本应用由个人通过 TRAE 智能AI辅助开发完成。" +
                    "\n\n采用 Kotlin + Jetpack Compose 构建，" +
                    "集成 MCP（Model Context Protocol）协议，" +
                    "结合免费 AI 接口实现 APK 文件的智能逆向分析。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            }

            // 开源组件
            InfoSection(
                icon = Icons.Default.Code,
                title = "开源组件"
            ) {
                val openSourceLibs = listOf(
                    Triple("Kotlin", "JetBrains", "Apache 2.0"),
                    Triple("Jetpack Compose", "Google", "Apache 2.0"),
                    Triple("OkHttp", "Square", "Apache 2.0"),
                    Triple("Gson", "Google", "Apache 2.0"),
                    Triple("Material Design 3", "Google", "Apache 2.0"),
                    Triple("Navigation Compose", "Google", "Apache 2.0"),
                    Triple("MCP Client", "Anthropic/Model Context Protocol", "MIT"),
                    Triple("Pollinations.ai", "Pollinations", "MIT"),
                    Triple("RikkaHub", "RikkaApps", "GPL-3.0"),
                )
                openSourceLibs.forEach { (name, author, license) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(130.dp)
                        )
                        Text(
                            "$author ($license)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "本应用基于上述开源组件构建，感谢开源社区的贡献。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 免责声明
            InfoSection(
                icon = Icons.Default.Warning,
                title = "免责声明"
            ) {
                Text(
                    "1. 本工具仅供学习、研究和技术交流使用，请勿用于任何非法商业用途。" +
                    "\n\n2. 使用本工具所产生的一切后果由使用者本人承担，开发者不承担任何直接或间接责任。" +
                    "\n\n3. 本工具涉及的 AI 接口均为免费公开接口，可能存在不稳定性，开发者不对 AI 输出的准确性做出任何保证。" +
                    "\n\n4. 本工具不对任何被分析 APK 文件的内容负责，分析结果仅供参考。" +
                    "\n\n5. 使用者应遵守所在国家或地区的法律法规，不得利用本工具从事任何违法活动。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            }

            // 隐私声明
            InfoSection(
                icon = Icons.Default.Shield,
                title = "隐私声明"
            ) {
                Text(
                    "1. 本应用不收集、存储、上传任何用户个人数据。" +
                    "\n\n2. 本应用不包含任何第三方广告 SDK 或数据追踪 SDK。" +
                    "\n\n3. AI 对话内容仅用于当次会话，不会保存至任何远程服务器。" +
                    "\n\n4. 所有分析操作均在本地设备或连接的 MCP 服务上完成。" +
                    "\n\n5. 应用运行所需的网络权限仅用于与 AI 接口和 MCP 服务通信。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            }

            // 版权信息
            InfoSection(
                icon = Icons.Default.Info,
                title = "版权信息"
            ) {
                Text(
                    "本应用为开源免费软件，由开发者「文强哥 (Johnny520)」独立开发。" +
                    "\n\n开源协议：MIT License" +
                    "\n\n未经开发者书面授权，不得将本应用用于商业用途或进行反编译、二次分发。" +
                    "\n\n本应用使用的开源组件受其各自的许可证约束。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            }

            // 法律责任
            InfoSection(
                icon = Icons.Default.Warning,
                title = "法律责任"
            ) {
                Text(
                    "1. 使用本工具分析他人的应用程序可能侵犯他人的知识产权，使用者应自行承担相应的法律风险。" +
                    "\n\n2. 开发者不对本工具的完整性、可靠性做任何明示或暗示的保证。" +
                    "\n\n3. 对于因使用本工具而导致的任何直接、间接、附带、特殊或后果性损害，开发者不承担任何责任。" +
                    "\n\n4. 使用本工具即表示您已阅读并同意上述免责声明和隐私声明。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 20.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun InfoSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ClickableInfoRow(icon: ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(52.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** GitHub 品牌图标（"G" 方标） */
private val GithubIcon = ImageVector.Builder(
    name = "GitHub",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(
        fillAlpha = 1f,
        strokeAlpha = 0f
    ) {
        // 外框 — 圆角方形
        moveTo(12f, 2f)
        cubicTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
        cubicTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
        cubicTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
        cubicTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
        close()
        // G 字母
        moveTo(16.5f, 12f)
        cubicTo(16.5f, 14.48f, 14.48f, 16.5f, 12f, 16.5f)
        cubicTo(9.52f, 16.5f, 7.5f, 14.48f, 7.5f, 12f)
        cubicTo(7.5f, 9.52f, 9.52f, 7.5f, 12f, 7.5f)
        cubicTo(13.1f, 7.5f, 14.1f, 7.9f, 14.85f, 8.55f)
        lineTo(13.75f, 9.65f)
        cubicTo(13.3f, 9.2f, 12.7f, 8.95f, 12f, 8.95f)
        cubicTo(10.32f, 8.95f, 8.95f, 10.32f, 8.95f, 12f)
        cubicTo(8.95f, 13.68f, 10.32f, 15.05f, 12f, 15.05f)
        cubicTo(13.53f, 15.05f, 14.8f, 13.9f, 14.98f, 12.45f)
        lineTo(12f, 12.45f)
        lineTo(12f, 11f)
        lineTo(16.5f, 11f)
        lineTo(16.5f, 12f)
        close()
    }
}.build()
