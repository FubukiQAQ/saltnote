# SaltNote Alpha（MVP）开发文档

* **文档版本**：v0.1-alpha
* **产品定位**：永久免费开源、本地优先（Local-First）、零后端依赖、支持 BYOK 多模态 AI 的双模手账与学习笔记应用
* **目标平台（Alpha）**：Android 平板（ARM64 优先，支持压感手写笔） + 桌面端（Windows / macOS / Linux）
* **核心技术栈**：Rust Workspace（核心逻辑） + UniFFI 0.28+（跨语言桥接） + Compose Multiplatform / Skiko（跨端 UI 与干墨渲染） + Android 原生前缓冲手写层
* **开源协议建议**：核心引擎与客户端采用 AGPL-3.0 或 Apache-2.0（防止闭源套壳或鼓励生态共建，立项前需最终锁定）

---

## 1. Alpha 范围界定（Scope & Non-Goals）

Alpha 版本的核心目标是**在 8 周内跑通“手写/文档双模记录 → 随手圈多模态析出 → 本地 FSRS 复习 → 零后端多端同步”的最小完整闭环**，严格控制范围，防止功能膨胀拖垮首版交付。

| 模块 | Alpha 必须实现（P0 In-Scope） | 延后至 Beta / Release（Out of Scope） |
| --- | --- | --- |
| **存储与文档** | Vault 目录初始化；Loro CRDT 页面状态；`.md` 基础读写与 `assets/` 哈希存储 | 外部编辑器实时 File Watcher 双向回写；CRDT 历史碎片自动 GC 压缩 |
| **双模编辑器** | **画布模式**（压感钢笔/荧光笔/橡皮擦、图片插入、平移缩放）；**文档模式**（标准 Markdown + 行内嵌入画布块） | LaTeX 原生矢量公式排版（Alpha 暂用代码块/纯文本降级）；手账贴纸工坊与自由拼贴滤镜 |
| **随手圈（Salt Halo）** | 3 色内置指令笔（琥珀黄扩充、冰川蓝制卡、矿石灰 OCR）；局部 WebP 切片；可折叠盐晶气泡 + 一键熔合进正文 | 自定义颜色预设导出导入；多区域 600ms 联合圈选；气泡内多轮苏格拉底追问流 |
| **BYOK AI 路由** | OpenAI Chat Completion / Anthropic Messages / Gemini 全兼容；系统安全区存 Key；流式中断止损；`#private` 云端硬拦截 | 桌面端 SaltRelay 自动 mDNS 算力接力代理；本地正则出站脱敏；完整 Token 美元账本可视化 |
| **零后端同步** | **WebDAV 单写者增量同步**（兼容坚果云 / Nextcloud / Alist）+ 局域网 mDNS 设备发现与基础直连验证 | Cloudflare R2 / S3 / Google Drive / OneDrive 适配；端到端加密（E2EE）主口令轮换 UI |
| **本地检索与复习** | SQLite FTS5 全文搜索；FSRS 问答卡列表与基础复习界面（事件溯源日志重放） | ONNX INT8 本地 Embedding 语义联想（边缘微光）；画布图像遮挡卡；FSRS 本地参数拟合 |

---

## 2. 系统架构与物理目录规范

### 2.1 Alpha 分层架构

```text
+----------------------------------------------------------------------+
|                Compose Multiplatform UI (Kotlin / Skiko)             |
|   [Vault 导航]   [双模画布/Markdown视图]   [随手圈浮层]   [FSRS复习]   |
+----------------------------------+-----------------------------------+
| Android 平台层                   | Desktop 平台层                    |
| - Jetpack Ink / FrontBuffer 湿墨 | - 键鼠缩放与速度模拟压感          |
| - MotionEventPredictor           | - DPAPI / Keychain / libsecret    |
| - Android Keystore               | - 本地目录选择器                  |
+----------------------------------+-----------------------------------+
                     ▲ UniFFI (批处理二进制 / 异步回调)
                     ▼
+----------------------------------------------------------------------+
|                   Shared Rust Core (saltnote-core)                   |
|  salt-types  │  salt-crdt (Loro)  │  salt-canvas (rstar + 滤波)      |
|  salt-router (OpenAI/Gemini)      │  salt-sync (OpenDAL WebDAV/P2P)  |
|  salt-intel (SQLite FTS5 + fsrs)  │  salt-ffi (全局 Tokio 门面)       |
+----------------------------------------------------------------------+

```

### 2.2 Alpha Vault 物理目录结构

所有同步策略在 Alpha 阶段即按 V1.0 终态布局落盘，避免后续版本迁移数据：

```text
MySaltVault/
├── pages/                          # [可读视图] Alpha 阶段统一平铺或按月归档的 .md 文件
│   └── 2026-11-21_示例笔记.md
├── assets/                         # [同步/不可变] 按 SHA-256 前两位分桶的图片附件
│   └── 9f/9f86d081884c7d65.webp
└── .saltnote/
    ├── config/
    │   ├── router.json             # 槽位与供应商映射（严禁含 API Key）
    │   └── halo_presets.json       # 三色默认随手圈 Prompt 配置
    ├── sync/
    │   ├── manifests/              # 各设备最新同步游标 {device_id}.json
    │   └── deltas/{device_id}/     # 单写者追加式 Loro 增量包 {ts}_{seq}.delta
    └── local/                      # [硬编码忽略同步] 纯本机派生库与缓存
        ├── index.sqlite            # FTS5 全文索引 + fsrs_card_state 派生表
        ├── cache/slices/           # 随手圈临时裁剪 WebP 缓存
        └── device_state.json       # 本机 ULID device_id 与同步水位线

```

---

## 3. 核心模块技术规格

### 3.1 存储与 CRDT 核心（`salt-crdt`）

* **文档模型**：每个页面对应一个独立 `LoroDoc`，内部包含四个根容器：
* `meta`（`LoroMap`）：`page_id`、`title`、`mode`（`journal` / `study`）、`is_private`、`tags`。
* `body`（`LoroText`）：Markdown 正文真值，编辑停止防抖 1.5 秒后单向投影刷新至对应 `.md` 文件。
* `canvas`（`LoroMap`）：按 `canvas_id` 索引，存放不可变笔迹二进制块（`strokes`）、变换与删除墓碑（`transforms`）及图片节点（`images`）。
* `halos` & `cards`（`LoroMap` / `LoroList`）：存储盐晶批注混合锚点与追加式 `fsrs_logs`。


* **持久化策略**：本地编辑先写入内存 Loro 状态并立即追加至本地 WAL 日志防崩溃丢数据；切页或每 8 秒导出一次增量 `delta` 供同步层消费。

### 3.2 低延迟手写与矢量画布（`salt-canvas` + CMP）

* **湿墨/干墨解耦（Android 端核心指标）**：
* 落笔（`ACTION_DOWN` / `ACTION_MOVE`）期间完全在 Android 原生 `GLFrontBufferedRenderer`（或 `androidx.ink`）中直绘，循环提取 `MotionEvent.getHistorical*` 原始 240Hz 点集并接入 `MotionEventPredictor` 预测下一帧。
* 抬笔（`ACTION_UP`）瞬间清空湿墨层，将原始点打包为小端二进制流（每点 16 字节：`f32 x, f32 y, f32 pressure, u32 dt_ms`）一次性跨 UniFFI 传入 `salt-canvas`。


* **笔迹处理管线**：
1. **平滑滤波**：在 Rust 中对轨迹执行 **One-Euro Filter** 消除手抖。
2. **差分压缩**：转换为相对起点的整型增量数组持久化进 Loro，单条笔迹体积控制在百字节级。
3. **网格三角化与索引**：根据笔刷宽度与压感生成 Triangle Strip 顶点流写入内存缓存，并将包围盒插入该页的 `rstar` R-Tree。


* **干墨瓦片渲染**：CMP 层采用 `512 × 512 px` 离屏 Skia `Surface` 瓦片缓存。平移缩放期间直接变换 GPU 纹理保证 60/120fps，手势结束后按当前缩放等级（LOD）异步重绘视口内脏瓦片。

### 3.3 随手圈（Salt Halo）与 BYOK 路由（`salt-router`）

* **Alpha 三色预设规范**：

| 颜色 | `preset_id` | 视觉策略 | 目标输出 |
| --- | --- | --- | --- |
| 琥珀黄 `#F5C242` | `builtin.amber_expand` | 纯文本短路 / 含手写则切图 | 概念解释、类比与关键补充（Markdown） |
| 冰川蓝 `#4EA8DE` | `builtin.glacier_card` | 纯文本短路 / 含手写则切图 | 严格约束输出 1 至 3 组 `Q / A` 抽认卡草稿，支持一键入库 FSRS |
| 矿石灰 `#8D99AE` | `builtin.mineral_ocr` | 强制截取 WebP 切片 | 将手写公式、板书或草图清洗为整洁 Markdown 块 |

* **切片与上下文规则**：
* `resolve_lasso_selection` 在 R-Tree 中命中对象后，自动将包围盒向外扩展 12% Padding；密集公式/文字长边压缩至 `1280px`，粗笔手账压缩至 `768px`，编码为 WebP（质量 80%）。
* 拼接上下文：提取上方最近的 H1/H2 标题 + 前后各 200 字文本，组装发送。


* **BYOK 安全与成本底线**：
* API Key 经 Kotlin `PlatformSecretStore` 存入 Android Keystore / Windows DPAPI / macOS Keychain / Linux SecretService，`router.json` 仅保留 `vault://key_id` 引用句柄。
* 每个请求绑定 `tokio_util::sync::CancellationToken`，UI 关闭盐晶气泡立即触发 `handle.cancel()` 切断底层连接停止计费。
* 若 `page.is_private == true`，路由层硬校验目标节点必须为本地回环或 RFC1918 内网地址（如本机/局域网 Ollama），否则直接抛出 `SaltError::PrivacyBlocked`。



### 3.4 零后端同步引擎（`salt-sync`）

* **WebDAV 单写者增量协议（Alpha 主力通道）**：
* 底层通过 `opendal`（`services-webdav`）统一对接坚果云、Nextcloud 或本地 Alist。
* 上传：仅向 `.saltnote/sync/deltas/{my_device_id}/` 写入新文件，采用“先 `PUT *.tmp` 再 `MOVE` 正式名”保证原子性；附件先发 `HEAD` 探测，存在即跳过。
* 拉取：仅轮询 `.saltnote/sync/manifests/*.json`，对比本地水位线后按序下载缺失的远端 `.delta` 文件并喂入 Loro `import()`；遇到 HTTP 429 自动指数退避（2s → 4s → 8s → 30s）。


* **局域网 P2P 基础通道**：
* 集成 `mdns-sd` 广播 `_saltsync._tcp.local`，基于自签名 Ed25519 证书与扫码指纹交换建立 `quinn`（QUIC/TLS 1.3）连接，实现同 Wi-Fi 下平板与电脑免云盘交换 Loro State Vector 增量包。



### 3.5 本地检索与 FSRS 闭环（`salt-intel`）

* **事件驱动更新**：订阅 `salt-crdt` 的 `DocChangeEvent` 广播，在低优先级后台线程更新 `index.sqlite`：
* 文本变更 → 写入 SQLite FTS5 虚表（集成 `lindera` 分词），支持毫秒级关键词高亮检索。
* 卡片创建或复习提交 → 将 `ReviewLog { log_id, card_id, rating, reviewed_at_utc, duration_ms, device_id }` 写入 Loro；重放引擎按时间戳排序去重（合并 15 分钟内跨端重复打卡），调用 `fsrs-rs` 默认参数重算 `stability`、`difficulty` 与 `due_at_utc` 落入 `fsrs_card_state`。


* **Alpha 复习界面**：按 `due_at_utc` 展示今日待复习队列，支持显示正面 → 点击揭晓背面 → 1 至 4 档评分（重来 / 困难 / 良好 / 简单）→ 一键跳转回原笔记锚点。

---

## 4. 开发环境与构建工作流

### 4.1 基础工具链要求

* **Rust**：Stable 1.82+，安装目标平台：`aarch64-linux-android`、`x86_64-linux-android` 及当前桌面宿主 Target。
* **Android 构建**：JDK 17+、Android SDK（API 35）、Android NDK r26d+、`cargo-ndk`（`cargo install cargo-ndk`）。
* **UniFFI 绑定**：使用仓内 `rust/uniffi-bindgen` 保证与核心 crate 版本严格一致。

### 4.2 常用开发与验证命令

```bash
# 1. 纯 Rust 核心单元测试（脱离移动端快速验证 CRDT、R-Tree、FSRS 与 WebDAV 合并逻辑）
cargo test --workspace

# 2. 编译桌面端原生库并生成 Kotlin UniFFI 绑定
./gradlew :app:nativeBridge:buildRustDesktop :app:nativeBridge:generateUniFFIBindings

# 3. 启动桌面端开发调试（Windows / macOS / Linux）
./gradlew :app:composeApp:run

# 4. 交叉编译 Android ARM64 动态库并安装至平板真机（压感手写必须在真机验证）
./gradlew :app:nativeBridge:buildRustAndroidArm64 :app:composeApp:installDebug

```

> **Windows 桌面端开发注意**：由于 JVM 加载原生 `.dll` 后会锁定构建目录，重复执行打包或重新编译 Rust 动态库前，请确保已完全关闭运行中的桌面端进程并执行 `./gradlew --stop` 释放文件句柄。

---

## 5. Alpha 8 周开发里程碑计划

| 阶段 | 周期 | 核心目标与交付物 | 阶段出口验收（Spike / Gate） |
| --- | --- | --- | --- |
| **M0：工程脚手架与手写 Spike** | 第 1 周 | 搭建 Monorepo、Gradle-Rust-UniFFI 自动化链路；在 Android 平板真机跑通 Jetpack Ink 前缓冲湿墨 + Skiko 干墨落盘 Demo | **核心风险验证通过**：真机压感笔落笔延迟低于 15ms；UniFFI 二进制传笔迹无卡顿无内存泄漏 |
| **M1：双模存储与基础画布** | 第 2 至 3 周 | 完成 `salt-crdt` Vault 读写与 `.md` 投影；实现画布模式（3 种笔刷、橡皮、缩放平移、插图）与文档模式基础编辑 | 单页加载 5,000 条笔迹平移缩放稳定 60fps；杀进程重启后 100% 恢复未丢失笔迹与文本 |
| **M2：随手圈与 BYOK 闭环** | 第 4 至 5 周 | 完成 `salt-canvas` R-Tree 智能吸附与 WebP 局部切片；完成 `salt-router`（OpenAI 兼容 + Gemini）与安全区存 Key；上线三色盐晶气泡与熔合 | 圈选板书照片在 3 秒内流式返回注释；中途关气泡立即断开网络请求；`#private` 页面阻断云端调用 100% 生效 |
| **M3：零后端同步与 FSRS** | 第 6 至 7 周 | 完成 `salt-sync` WebDAV 单写者同步 + 局域网 mDNS/QUIC 原型；完成 `salt-intel` FTS5 检索与 FSRS 复习闭环 | 平板与电脑断网各改同一页、各复习同一张卡，联网经坚果云同步后无冲突副本且 FSRS 调度时间完全一致 |
| **M4：Alpha 加固与公测发布** | 第 8 周 | 补齐首次启动“零配置离线体验”引导页、API Key 连通性测试、导出标准 Markdown 备份；修复高优崩溃并发布 GitHub Alpha Release | 全平台安装包构建通过 CI；完成下节所有质量红线压测与安全审计 |

---

## 6. Alpha 质量验收标准与测试红线（Definition of Done）

发布 `v0.1.0-alpha` 前，以下硬性指标必须全部达标并通过自动化测试或真机人工回归：

### 6.1 性能红线

* **Android 真机手写延迟**：使用高速摄影或系统 `adb shell dumpsys gfxinfo` / 帧时间戳实测，湿墨落笔到显示延迟不超过 **15ms**（目标 ≤ 12ms）。
* **大画布流畅度**：单页 10,000 条笔迹 + 10 张插图场景下，平移缩放帧率稳定 ≥ 60fps（高刷平板目标 120fps），无 GC 锯齿卡顿。
* **冷启动与内存**：桌面端冷启动至可输入正文 ≤ 1.0 秒，空闲内存 ≤ 180MB；Android 端基础 APK 体积 ≤ 35MB。

### 6.2 同步与混沌测试矩阵（必须在 Rust 集成测试中自动化）

1. **并发离线编辑冲突测试**：模拟 3 台虚拟设备离线并发修改同一段 Markdown、同时新增笔迹并拖动同一对象，合并全部 `.delta` 后校验所有设备最终 Loro 状态哈希比特级一致。
2. **WebDAV 限流与半截文件测试**：通过 Mock WebDAV 服务器随机注入 HTTP 429 与连接中断，验证客户端退避重试成功且远端不残留损坏的正式 `.delta` 文件。
3. **FSRS 重复复习重放测试**：模拟双端在 5 分钟内离线对同一卡片分别打分，同步重放后确认仅生效一次有效复习记录，不出现稳定性指数翻倍膨胀。

### 6.3 隐私与安全审计清单

* 对同步后的整个 WebDAV 目录执行全局正则扫描，确认不存在任何 `sk-`、`AIza` 或用户填入的 API Key 明文片段。
* 使用抓包工具（Charles / mitmproxy）监控客户端出站流量：确认未配置云同步与 API Key 时，软件保持**绝对零外部网络请求**（除局域网 mDNS 广播外）；确认标记 `#private` 的页面触发随手圈时绝不向公网域名发包。
* 检查崩溃日志导出文件，确认所有 `Authorization`、`x-api-key` 请求头及用户正文内容均已自动脱敏抹除。