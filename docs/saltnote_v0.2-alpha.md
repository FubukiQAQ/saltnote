# SaltNote v0.2-alpha 核心改进开发文档：移动端“盐池采集”与“碎片化 FSRS 复习”

*   **文档版本**：v0.2-alpha（增量演进规格书）
*   **演进基线**：基于 `v0.1-alpha`（Android 平板低延迟手写 + 桌面双模编辑 + 随手圈 + Loro CRDT / WebDAV & P2P 同步 + FSRS 核心）
*   **本版核心目标**：在不分叉代码库的前提下，将 Android 手机端正式纳入 SaltNote 闭环，补齐**“随时拍照/语音丢入盐池”**与**“通勤 5 分钟碎片化 FSRS 刷卡复习”**两大高频移动场景，形成**平板深度学习 + 手机碎片采集与复习 + PC 端本地算力接力**的三端协同体系。
*   **架构原则**：零后端依赖、端侧离线优先、Compose Multiplatform 单仓自适应 UI、Rust 单一真值内核。

---

## 1. 演进背景与三端协同闭环

### 1.1 解决的核心痛点
在 `v0.1-alpha` 中，平板与桌面端已经覆盖了教室、书房等“整块沉浸学习”场景，但真实学习与生活记录存在两个明显的断层：
1.  **高摩擦采集断层**：走在路上、听讲座或翻阅纸质书时，不方便掏出平板和手写笔；用手机自带相册或备忘录随手拍下板书/录下灵感后，内容往往沉睡在系统相册里，永远不会回流到学习笔记库中。
2.  **碎片复习断层**：FSRS 复习依赖高频、短时的记忆提取，但打开平板或电脑复习启动成本过高；地铁、排队等 5 分钟零散时间白白流失，导致复习队列积压并引发逾期挫败感。

### 1.2 三端软硬协同数据流

```text
+-----------------------------------+       局域网 P2P (mDNS/QUIC)       +-----------------------------------+
|      Android 手机端 (v0.2新增)     | <===============================> |       Android 平板端 (v0.1基线)    |
|  - 桌面微件/快捷磁贴 1 秒入盐池     |        或 WebDAV 增量漫游          |  - 低延迟压感手写 & PDF 批注       |
|  - 板书拍照压缩 + Opus 语音速记     |                                   |  - 侧边“盐池抽屉”一键拖入画布       |
|  - 通勤 5 分钟单手 FSRS 碎片刷卡    |                                   |  - 随手圈 (Salt Halo) 多模态析出   |
+-----------------+-----------------+                                   +-----------------+-----------------+
                  |                                                                       |
                  +----------------------------+             +----------------------------+
                                               |             |
                                               v             v
                                  SaltRelay 局域网 mTLS 算力接力 (免费/隐私)
                                  +--------------------------------------+
                                  |        PC 桌面端 (Win/Mac/Linux)     |
                                  |  - 深度 Markdown 结构化整理与归档     |
                                  |  - 共享本机 Ollama / Whisper 算力     |
                                  +--------------------------------------+
```

### 1.3 v0.2-alpha 范围界定（Scope）

| 能力维度 | v0.2-alpha 必须交付（P0 In-Scope） | 延后至 v0.3 / Beta（Out of Scope） |
| :--- | :--- | :--- |
| **手机端适配壳** | 基于 `WindowSizeClass` 的紧凑屏（Compact）自适应导航；移动端轻量只读/基础 Markdown 浏览 | 手机小屏上的重型压感自由手写画布（保留至平板端） |
| **盐池极速采集** | 一键拍照（CameraX + 自动压缩为 WebP）；单手语音速记（Opus 编码）；系统分享（Share Intent）收件；桌面快捷微件 | 端侧离线 OCR 全页识别；实时流式语音转写字幕；智能手表端独立录音 App |
| **盐池跨端流转** | 手机入库 -> CRDT 同步 -> 平板/桌面端“盐池抽屉”展示 -> 一键拖入当前页面并可选自动触发随手圈解析 | AI 全自动按学科归类并建页（需结合完整本地向量索引） |
| **5 分钟碎片复习** | 时间盒微会话生成器（默认 5 分钟）；单手拇指滑动/点按刷卡；临界遗忘抢救排序；原位切片轻量预览；纯离线复习与无冲突合并 | 语音朗读听背模式（TTS）；桌面端与手机端联动番茄钟统计 |
| **移动弱网保护** | 蜂窝网络省流模式（优先同步文本、FSRS 日志与缩略图，大附件推迟至 Wi-Fi） | 后台静默 WorkManager 定时唤醒同步（按系统省电策略渐进开放） |

---

## 2. 核心功能一：移动端“盐池（Salt Inbox）”零摩擦采集

“盐池”的设计准则是**“只管先丢进来，绝对不问分类、标题和标签”**，将单次采集从掏出手机到完成锁屏压进 5 秒以内。

### 2.1 多入口零阻力唤起
*   **Android 桌面微件（Glance App Widget）**：提供 $4 \times 1$ 极简胶囊微件，包含三个直达按钮——**拍照入池**、**语音入池**、**今日碎片复习**（右侧附带轻量待复习圆点提示，不展示刺眼红字逾期数）。
*   **系统下拉快捷磁贴（Quick Settings Tile）**：支持在锁屏或任意 App 顶部下拉一步启动语音速记或相机采集。
*   **Android 系统分享接收（`ACTION_SEND`）**：在浏览器、微信或电子书阅读器中选中文段或图片，点击系统“分享 -> 丢入 SaltNote 盐池”，后台静默写入并弹出轻提示，不打断原 App 阅读。

### 2.2 极速拍照采集（板书 / 书页 / 手账素材）
*   **轻量化拍摄管线**：集成 Android Jetpack `CameraX`，打开直接进入取景页，提供“板书/文档模式”（开启轻度对比度增强与白平衡校正）与“手账生活模式”（保留原色彩）一键切换。
*   **边缘压缩与去重落盘**：
    *   拍摄完成后在后台协程立即压缩：文档/板书长边限制在 $1600\text{ px}$ 以内，手账随拍限制在 $1280\text{ px}$ 以内，统一编码为 WebP（质量 $82\%$），同时生成一张 $320\text{ px}$ 宽的轻量缩略图用于弱网预览。
    *   计算内容 `SHA-256` 哈希写入 `assets/{h0..h1}/{hash}.webp`，在 Loro CRDT 中仅追加极轻量的 `InboxItem` 元数据，支持拍摄后顺手补充一句语音或简短备注。

### 2.3 极速语音速记与异步转写路由
*   **高压缩比音频落盘**：采用 **Opus** 编码（单声道、$16\text{ kHz}$、$24\text{ kbps}$），每分钟语音体积仅约 $180\text{ KB}$，既保证人声清晰度又极大降低 WebDAV 同步流量。录音期间实时写入本地临时文件，防止系统杀后台或来电中断导致内容丢失。
*   **三段式按需转写策略（严格恪守 BYOK 与隐私隔离）**：
    1.  **离线先落盘**：录音结束瞬间即入池并打上 `transcript_status = "pending"`，绝不因为无网或未配 API 而阻塞用户。
    2.  **隐私路由拦截**：用户在录音页一键勾选“私密日记（`#private`）”时，强制锁定仅允许回传已配对的局域网 PC 端本地转写服务（如本地 Whisper / Ollama 多模态模型），严禁发送至公网 API。
    3.  **多通道异步转写**：连网或回到局域网后，通过 `SaltRouter` 自动路由至用户配置的低价音频模型（如 Gemini Flash 原生音频输入或 OpenAI 兼容 Whisper 接口）或 PC 端 SaltRelay，将转写文本与关键摘要自动回填至该 `InboxItem`，并保留原始音频供点击回听。

### 2.4 平板 / 桌面端的“消盐”流转
*   平板端和桌面端编辑器右侧新增可折叠的**“盐池抽屉（Salt Tray）”**，实时列出所有 `state = "unprocessed"` 的手机采集项。
*   **拖拽即融入**：用户用手指或手写笔将盐池中的板书照片或语音卡片直接拖进画布或 Markdown 文档，系统自动将其转化为原生图片节点/引用文本块，并自动把该条 `InboxItem` 标记为 `archived`（同时保留双向溯源 ID）。
*   **一键随手圈析出**：在盐池抽屉中长按某条板书照片，可直接调用默认色笔（如矿石灰 OCR 或冰川蓝制卡），无需先贴进画布即可完成知识析出。

---

## 3. 核心功能二：通勤 5 分钟碎片化 FSRS 刷卡复习

移动端复习体验的核心定位不是“大块时间考前突击”，而是利用等车、地铁、课间等碎片间隙，提供**低心理负担、单手可操、随时可停**的微复习闭环。

### 3.1 “5 分钟微会话”动态预算与抗挫败排期
为避免打开软件看到“今日待复习 248 张”直接劝退，手机端默认采用**时间盒微会话（Micro-Session）**机制：

1.  **动态时间预算装填**：
    *   Rust 核心统计用户近期单卡平均响应耗时 $t_{\text{avg}}$（冷启动默认 $18\text{ s}$/张），结合目标会话时长 $T_{\text{budget}}$（预设 3 分钟 / **5 分钟** / 10 分钟三档），动态计算本轮装填卡片数：
        $$N_{\text{session}} = \text{clamp}\left(\left\lfloor \frac{T_{\text{budget}}}{t_{\text{avg}}} \right\rfloor,\ 5,\ 30\right)$$
    *   界面顶部不显示令人生畏的总积压数，仅显示本轮柔和进度条（例如 `4 / 15 · 约剩 3 分钟`），刷完一轮弹出正向反馈，并询问“再来 3 分钟”或“今天到此为止”。
2.  **临界抢救优先队列（基于 FSRS 可提取性）**：
    *   针对每张到期卡片，利用 FSRS 实时计算当前记忆可提取性：
        $$R(t, S) = \left(1 + \frac{19}{81} \cdot \frac{t}{S}\right)^{-0.5}$$
    *   当用户开启“温和抢救模式”或当日精力打卡偏低时，优先抽取 $R \in [0.70, 0.90]$ 区间内的“临界遗忘卡”装入 5 分钟微会话——这类卡片稍加提示即可成功回忆，能最大化单位时间的记忆挽救效率与成就感；对深度遗忘卡（$R < 0.50$）按比例限量混排（每轮不超过 $20\%$）。

### 3.2 单手拇指交互与“原位切片回溯”
*   **热区下沉的单手 UI**：
    *   卡片主体居中偏上，底部 $35\%$ 区域为纯拇指操作区。
    *   **看正面 -> 轻触屏幕任意位置翻转看背面 -> 底部四键打分（重来 / 困难 / 良好 / 简单）**，或开启极客手势（左滑重来、右滑良好、上滑简单）。
*   **轻量化“原位切片回溯”（解决小屏看手写笔记难题）**：
    *   手机端不加载平板完整的矢量笔迹大画布，避免小屏卡顿和排版错乱。
    *   若该卡片由平板“随手圈”生成，点击卡片右上角“查看原笔记”时，客户端直接弹出该随手圈保留的局部高亮 WebP 切片（支持双指放大查看板书与手写公式）及同页前后 $200$ 字 Markdown 摘要；只有点击“进入完整页面”时才按需渲染整页文档。

### 3.3 地铁无网纯离线复习与确定性同步
*   手机端所有待复习卡片与所需切片缩略图均缓存在本地 Vault 与 `index.sqlite` 中，完全脱离网络运行。
*   每次评分即时生成不可变事件 `ReviewLog { log_id, card_id, rating, reviewed_at_utc, duration_ms, device_id }` 写入本地 Loro 文档并立刻刷新本地派生调度表。
*   出地铁恢复网络后，客户端仅上传数 KB 的 `.delta` 增量文件（不上传大附件）；若平板离线时也恰好复习了同一张卡，底层遵循 `v0.1-alpha` 的 15 分钟防抖重放规则，确保全端调度状态比特级一致。

---

## 4. 跨端自适应架构与数据/接口扩展

### 4.1 Compose Multiplatform 零分叉自适应布局
为了避免维护两套 Android 代码，手机端与平板端共用同一个 `:app:composeApp` APK，运行时依据 Material 3 `WindowSizeClass` 自动切换视图形态：

| 屏幕宽度分级 | 典型设备 | 导航形态 | 默认首页 | 画布行为 |
| :--- | :--- | :--- | :--- | :--- |
| **Compact**（宽度 $< 600\text{ dp}$） | Android 手机 | 底部 3 栏导航（盐池 / 复习 / 笔记）+ 中央悬浮采集胶囊 | **碎片中枢页**（顶部 5 分钟复习卡 + 下方盐池时间流） | 静态高清预览 + 双指缩放浏览；点击局部文本可做轻量 Markdown 修订 |
| **Medium / Expanded**（宽度 $\ge 600\text{ dp}$） | Android 平板 / 桌面端 | 左侧导航栏 + 右侧可折叠盐池抽屉 | **双模主工作区**（画布 / Markdown 编辑器） | 完整低延迟湿墨前缓冲 + 干墨瓦片金字塔 + 随手圈记号笔 |

### 4.2 新增 CRDT 数据结构（`InboxItem`）
在 Vault 级 Loro 根文档中新增 `inbox`（`LoroMap`，键为 `inbox_id`）存放盐池采集项，遵循不可变附件引用原则：

```json
{
  "inbox_id": "inbx_01JDF8K2M9P3Q7R1S5T8V0W2X4",
  "kind": "photo_note",
  "created_at_utc": 1763820120,
  "source_device_id": "android_phone_x9k2",
  "is_private": false,
  "state": "unprocessed",
  "payload": {
    "text_note": "高数课黑板上的格林公式例题，回去整理",
    "image_asset_hash": "sha256:7c4a8d09ca3762af",
    "thumb_asset_hash": "sha256:2b91e044f1c80a12",
    "audio_asset_hash": null,
    "audio_duration_ms": 0
  },
  "ai_enrichment": {
    "transcript_status": "not_applicable",
    "transcript_text": null,
    "auto_tags": []
  },
  "consumption": {
    "consumed_page_id": null,
    "consumed_block_id": null,
    "consumed_at_utc": null
  }
}
```

### 4.3 新增 Rust UniFFI 接口契约（`salt-ffi` 增量）

在现有 `SaltEngine` 门面上新增 `inbox()` 服务句柄，并在 `IntelService` 中补充微会话调度接口：

```rust
#[derive(uniffi::Enum)]
pub enum InboxKind {
    QuickText,
    PhotoNote,
    VoiceMemo,
    WebClip,
}

#[derive(uniffi::Record)]
pub struct InboxCaptureInput {
    pub kind: InboxKind,
    pub text_note: Option<String>,
    /// 已在移动端预压缩后的 WebP 字节或 Opus 音频字节，由 Rust 计算 SHA-256 存入 assets/
    pub primary_media_bytes: Option<Vec<u8>>,
    pub primary_media_ext: Option<String>,
    pub thumb_webp_bytes: Option<Vec<u8>>,
    pub audio_duration_ms: u32,
    pub is_private: bool,
}

#[derive(uniffi::Record)]
pub struct InboxItemView {
    pub inbox_id: String,
    pub kind: InboxKind,
    pub created_at_utc: i64,
    pub is_private: bool,
    pub state: String,
    pub text_note: Option<String>,
    pub transcript_text: Option<String>,
    pub transcript_status: String,
    pub thumb_asset_path: Option<String>,
    pub primary_asset_path: Option<String>,
}

#[derive(uniffi::Object)]
pub struct InboxService;

#[uniffi::export(async_runtime = "tokio")]
impl InboxService {
    /// 手机端零阻力入池：毫秒级写盘并返回 inbox_id，不等待网络与转写
    pub fn capture_item(&self, input: InboxCaptureInput) -> Result<String, SaltError> {
        todo!()
    }

    /// 列出盐池条目（平板“盐池抽屉”与手机首页共用）
    pub fn list_items(&self, include_archived: bool, limit: u32) -> Result<Vec<InboxItemView>, SaltError> {
        todo!()
    }

    /// 对语音或板书图片触发异步转写/摘要（受 #private 与 SaltRouter 隐私墙约束）
    pub async fn enrich_item(&self, inbox_id: String) -> Result<InboxItemView, SaltError> {
        todo!()
    }

    /// 平板/桌面端将盐池条目拖入目标页面后调用，原子完成消费标记与回链绑定
    pub fn consume_into_page(
        &self,
        inbox_id: String,
        target_page_id: String,
        target_block_id: String,
    ) -> Result<(), SaltError> {
        todo!()
    }
}

#[derive(uniffi::Record)]
pub struct MicroSessionPlan {
    pub session_id: String,
    pub budget_seconds: u32,
    pub estimated_seconds: u32,
    pub cards: Vec<ReviewCardView>,
    pub total_due_count: u32,
}

#[uniffi::export(async_runtime = "tokio")]
impl IntelService {
    /// v0.2 新增：按通勤时间盒与精力档位动态生成抗挫败微复习会话
    pub async fn build_micro_review_session(
        &self,
        budget_seconds: u32,
        energy_level: u8,
        rescue_mode: bool,
    ) -> Result<MicroSessionPlan, SaltError> {
        todo!()
    }
}
```

---

## 5. 移动端弱网、流量与隐私守卫策略

1.  **分级流量同步（Metred-Network Guard）**：
    *   Kotlin 侧监听 Android `ConnectivityManager` 网络类型并传递给 `salt-sync`。
    *   在 **蜂窝移动网络（4G/5G）** 下默认启用“轻量同步策略”：立即同步所有 `.delta` 增量（包含文字、FSRS 复习日志、盐池元数据）以及 $320\text{ px}$ 缩略图与 Opus 语音；对大于 $2\text{ MB}$ 的 PDF 和高分辨率附件自动挂起，等待接入 Wi-Fi 后经局域网 P2P 或 WebDAV 静默补齐。
2.  **随手圈切片的移动端友好缓存**：
    *   平板端通过随手圈生成的复习卡，在创建时自动把对应区域的压缩 WebP 切片（通常仅 $30\text{ KB}$ 至 $80\text{ KB}$）持久化入 `assets/`，确保手机端通勤复习时无需同步几十 MB 的原版 PDF 也能清晰看到原题与手写批注。
3.  **私密采集防漏网**：
    *   手机端录音或拍照点选 `#private` 锁形图标后，即使手机处于 5G 外网且配置了云端 Gemini/OpenAI API，客户端也会主动将 `transcript_status` 置为 `waiting_for_lan_relay`，直到回到宿舍/书房连上局域网 PC 的本地算力后才自动在局域网内完成转写。

---

