# SaltNote Alpha (v0.1-alpha)

永久免费开源（Apache-2.0）、本地优先（Local-First）、零后端依赖、支持 BYOK 多模态 AI 与 Material Design 3 界面的双模手账与学习笔记应用。

## 核心架构

```text
+----------------------------------------------------------------------+
|         Compose Multiplatform Material Design 3 UI (Kotlin / Skiko)  |
|   [Vault 导航]   [双模画布/文档视图]   [随手圈盐晶气泡]   [FSRS复习]  |
+----------------------------------+-----------------------------------+
| Android 平台层 (:app:androidApp) | Desktop 平台层 (:app:composeApp)  |
| - SurfaceView 240Hz 湿墨层       | - 键鼠缩放与速度模拟压感          |
| - MotionEventPredictor           | - Windows DPAPI / Keychain /      |
| - Android Keystore (AES-GCM)     |   libsecret                       |
+----------------------------------+-----------------------------------+
                     ▲ UniFFI 0.28+ (:app:nativeBridge)
                     ▼
+----------------------------------------------------------------------+
|                   Shared Rust Core (rust/crates/*)                   |
|  salt-types  │  salt-crdt (Loro)  │  salt-canvas (rstar + 1€ 滤波)   |
|  salt-router (OpenAI/Anthropic/Gemini/Ollama)                        |
|  salt-sync (OpenDAL WebDAV + mDNS/QUIC Ed25519)                      |
|  salt-intel (SQLite FTS5 + FSRS-rs) │ salt-ffi (全局 Tokio 门面)     |
+----------------------------------------------------------------------+
```

## 快速构建与验证

### 1. Rust 核心单元与混沌测试
```bash
cd rust
cargo test --workspace
```

### 2. 编译桌面端原生库并生成 Kotlin UniFFI 绑定
```bash
./gradlew :app:nativeBridge:buildRustDesktop :app:nativeBridge:generateUniFFIBindings
```

### 3. 运行桌面端 FFI 端到端测试 / 启动桌面端
```bash
./gradlew :app:nativeBridge:desktopTest
./gradlew :app:composeApp:run
```

### 4. 交叉编译 Android ARM64 动态库并构建 / 安装 APK
```bash
./gradlew :app:nativeBridge:buildRustAndroidArm64 :app:androidApp:assembleDebug
# 真机已通过 ADB 连接时：
./gradlew :app:nativeBridge:buildRustAndroidArm64 :app:androidApp:installDebug
```

> **Windows 桌面端注意**：重新编译 `saltnote_ffi.dll` 前，请确保已关闭桌面端进程并执行 `./gradlew --stop` 以释放 DLL 文件句柄。

## 开源协议

本项目采用 [Apache License 2.0](LICENSE)。
