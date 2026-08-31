# Quick-Share-Android

<div align="center">

# ⚡ Quick Share (Android 客户端 / 服务端)

**极速、轻量、高吞吐的 Android 原生局域网文件传输应用**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.0-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-SDK%2026%2B-green.svg?logo=android)](https://developer.android.com)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4.svg?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Protocol](https://img.shields.io/badge/Protocol-QuickShare%20v300-orange.svg)](#传输协议)
[![Tests](https://img.shields.io/badge/Tests-964%20Passed-brightgreen.svg)](#构建与测试)

</div>

---

## 📖 项目简介

**Quick-Share-Android** 是一款基于 Kotlin 与 Jetpack Compose Material 3 构建的高性能 Android 局域网极速文件互传应用。配合桌面端 [Quick-Share-PC](https://github.com/naruse-love/Quick-Share-PC)，实现 Android 手机与 Windows PC 之间免流、高速、低延迟的大文件与文件夹批量双向互传。

本项目彻底重构移除了传统多物理网卡切片捆绑的复杂依赖，采用经过优化的**纯局域网高吞吐单流 TCP 管道**，结合 8×1MB 零 GC 预分配内存缓冲池与 64 位随机写入存储引擎，充分释放千兆 Wi-Fi 与以太网的极限 IO 性能。

---

## ✨ 核心特性

- 🚀 **纯局域网极速快传**：针对 Wi-Fi 6 / 5G Wi-Fi / 以太网优化的高性能流式 TCP 传输管道，传输过程无需消耗外网流量，轻松跑满局域网物理带宽。
- 🔄 **双向工作模式支持**：
  - **客户端模式 (Client)**：手机主动连接 PC 端，支持自定义输入目标 IP 与**任意端口**（如 5740, 18888, 29999），支持常用连接历史与快速重连。
  - **服务端模式 (Server)**：手机端一键开启本地监听服务，自定义监听端口，动态展示本机局域网 IP，支持 PC 端主动连接。
- 📦 **大文件与文件夹无缝互传**：
  - 支持多层级目录递归遍历与保留目录结构完整还原。
  - 自动保留文件的最后修改时间（Last Modified Timestamp）。
- ⚡ **零 GC 缓冲与流式存储引擎**：
  - 8×1MB 预分配阻塞队列缓冲池（`BufferPool`），传输期间零对象分配，避免 GC 卡顿。
  - 适配 Android 11+ 全文件管理权限（`MANAGE_EXTERNAL_STORAGE`）与 Storage Access Framework (SAF)。
- 🎨 **现代化 Jetpack Compose Material 3 界面**：
  - 自适应深浅色主题、现代排版与微交互动画。
  - **双标签文件管理器**：支持本地与远端双标签切换、Unix/Windows 路径面包屑导航、多选批量操作。
  - **实时传输仪表盘**：动态仪表盘、瞬时传输速率计量、累计流量柱状图、传输总进度条与剩余时间估算（ETA）。
- 🛡️ **系统级后台保活与通知**：
  - 传输期间启动 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` 前台服务。
  - 申请并安全管理 `PARTIAL_WAKE_LOCK` 与 `WifiLock`，防止传输期间系统休眠或锁屏断连。
  - 系统通知栏动态更新传输进度并提供快捷取消按钮。

---

## 🏗️ 架构与模块设计

```
com.quickshare.android/
├── protocol/               # 协议层：QuickShare v300 协议常量、Big-Endian 二进制流编解码
│   ├── QuickShareConstants.kt
│   ├── QuickShareStream.kt
│   └── IQuickShareStream.kt
├── model/                  # 核心数据模型
│   ├── FileBlock.kt        # 1MB 数据切片实体
│   ├── QuickShareDirectory.kt     # 跨平台路径与文件元数据模型
│   ├── RemoteFile.kt       # 远端文件项
│   ├── TransferTask.kt     # 传输任务状态
│   └── TrafficInfo.kt      # 速率与流量统计
├── network/                # 网络传输引擎
│   ├── QuickShareClient.kt        # 客户端连接握手与指令调度
│   ├── QuickShareServer.kt        # 服务端本地监听与并发通道管理
│   ├── InterfaceEnumerator.kt # 本机网络接口检测
│   └── TrafficManager.kt   # 1 秒滑动窗口测速器
├── transfer/               # 分块流水线与存储引擎
│   ├── BufferPool.kt       # 8x1MB 零 GC 预分配内存池
│   ├── StorageManager.kt   # Direct IO 与 SAF 存储访问抽象
│   ├── ReadFileCall.kt     # 目录递归遍历与流式分块读取
│   ├── WriteFileCall.kt    # 乱序/顺序分块组装与 64 位随机写入
│   ├── SendFileCall.kt     # 二进制帧发送器
│   └── ReceiveFileCall.kt  # 二进制帧接收器
├── service/                # 系统服务
│   └── TransferForegroundService.kt # 前台保活与通知栏进度
├── ui/                     # Jetpack Compose UI
│   ├── screens/            # 页面：ConnectionScreen, ServerModeScreen, FileBrowserScreen, TransferDashboardScreen
│   ├── components/         # 组件：QuickShareTopAppBar, QuickShareBottomBar, SpeedGauge, StatusBadge
│   ├── viewmodel/          # MVVM 状态机与控制器
│   └── theme/              # Material 3 主题配色与排版
└── di/                     # 依赖注入容器 (AppContainer)
```

---

## 🛠️ 构建与测试

### 环境要求
- **Android Studio**：Ladybug (2024.2+) 或更高版本
- **JDK**：17 或更高版本
- **Android SDK**：Min SDK 26 (Android 8.0), Target SDK 35 (Android 15)

### 编译 Debug APK
```powershell
# 在 Quick-Share-Android 根目录下执行
.\gradlew.bat assembleDebug
```
编译产物位于：`app/build/outputs/apk/debug/app-debug.apk`

### 运行单元与集成测试套件
```powershell
.\gradlew.bat testDebugUnitTest
```
包含协议编解码、分块读写流水线、双模式网络通信、ViewModel 状态机与前台服务的全套测试用例。

---

## 📲 安装与使用说明

### 1. 安装 APK
通过 ADB 直接安装至手机：
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 手机连接 PC（手机作为客户端）
1. 启动 PC 端 `Quick-Share-PC`，确保 PC 端服务处于运行状态并查看 PC 端的局域网 IP（例如 `192.168.1.100`）和端口（默认 `5740`）。
2. 确保手机与 PC 连接在同一个 Wi-Fi 或局域网路由器下。
3. 打开手机 App，在“连接”页面输入 PC 端的 IP 和端口，点击“连接”。
4. 连接成功后，切换至“文件”页面，即可浏览本地或 PC 远端文件，选中文件点击“发送”或“下载”开启极速互传。

### 3. PC 连接手机（手机作为服务端）
1. 打开手机 App，切换至“服务端”页面。
2. 配置期望的监听端口（如 `5740`），点击“启动服务”。
3. 页面将显示手机当前的局域网 IP 地址。在 PC 端输入该 IP 和端口即可连接手机。

---

## 📄 开源许可证

本项目遵循 [MIT License](LICENSE) 开源。
