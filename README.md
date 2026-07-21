# APK Analyzer

一款功能强大的 Android APK 分析工具，集成 Pollinations.ai 图像生成与可自定义 MCP 服务器调用功能。

## 功能特性

### 1. APK 文件分析
- 解析 APK 文件基本信息（包名、版本、SDK 版本等）
- 提取应用图标并显示
- 计算文件哈希值（MD5、SHA1、SHA256）
- 展示完整权限列表
- 列出 Activities、Services、Receivers、Providers
- 显示证书信息
- 展示原生库（.so 文件）
- 统计资源文件

### 2. Pollinations.ai 图像生成
- 内置 Pollinations.ai 免费文生图 API
- 支持自定义提示词、图像尺寸、种子
- 多模型选择（flux、turbo、sdxl、dall-e-3）
- 支持可选 API Key 配置

### 3. MCP 服务器调用
- 可自定义输入任意 MCP 服务器 SSE 地址
- 自动初始化 MCP 连接并获取工具列表
- 支持调用任意 MCP 工具并传入 JSON 参数
- 内置默认 Pollinations MCP 服务器支持
- 实时日志输出

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material3
- **网络**: OkHttp + Retrofit + SSE
- **图像加载**: Coil
- **架构**: MVVM + ViewModel

## 项目结构

```
app/src/main/java/com/apkanalyzer/
├── MainActivity.kt          # 主入口与底部导航
├── APKAnalyzerApp.kt        # Application 类
├── analyzer/
│   └── ApkAnalyzer.kt       # APK 解析核心
├── pollinations/
│   └── PollinationsClient.kt # Pollinations API 客户端
├── mcp/
│   └── McpClient.kt         # MCP SSE 客户端
├── model/
│   ├── ApkInfo.kt           # APK 数据模型
│   └── McpModels.kt         # MCP 协议数据模型
├── ui/
│   ├── MainViewModel.kt     # 主 ViewModel
│   ├── Screen.kt            # 导航路由定义
│   ├── screens/
│   │   ├── AnalyzerScreen.kt     # APK 分析界面
│   │   ├── PollinationsScreen.kt # 图像生成界面
│   │   ├── McpScreen.kt          # MCP 服务器界面
│   │   └── SettingsScreen.kt     # 设置界面
│   └── theme/
│       ├── Color.kt
│       └── Theme.kt
└── util/
    └── HashUtils.kt         # 哈希计算工具
```

## 构建方式

### 使用 Android Studio
1. 打开项目文件夹
2. 等待 Gradle 同步完成
3. 点击 Run 按钮构建并安装

### 使用命令行
```bash
./gradlew assembleDebug
```

APK 文件将输出至 `app/build/outputs/apk/debug/app-debug.apk`

## 使用说明

### APK 分析
1. 进入 "APK分析" 标签页
2. 点击右下角浮动按钮选择 APK 文件
3. 应用将自动解析并展示所有信息

### 图像生成
1. 进入 "Pollinations.ai" 标签页
2. 输入提示词，选择模型和尺寸
3. 点击 "生成图像" 按钮
4. 生成的图像会直接显示在界面上

### MCP 服务器
1. 进入 "MCP服务器" 标签页
2. 输入 MCP SSE 服务器地址（默认已填入 Pollinations MCP）
3. 点击 "连接" 按钮
4. 连接成功后，工具列表会自动加载
5. 点击工具右侧的播放按钮，输入 JSON 参数进行调用

## MCP 服务器配置示例

- **Pollinations MCP**: `https://gen.pollinations.ai/sse`
- **自定义 MCP**: 输入任何支持 SSE 传输的 MCP 服务器地址

## 注意事项

- Android 6.0+ 需要存储权限以读取 APK 文件
- 图像生成需要网络连接
- MCP 服务器调用需要目标服务器支持 SSE 传输协议

## 开源协议

MIT License
