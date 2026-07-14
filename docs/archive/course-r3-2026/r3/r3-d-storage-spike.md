# R3-D 存储与 asset 映射 —— 技术调研（spike）

> 文档状态：调研稿（已被 R3 plan 采纳，保留作为实现参考）
> 建立时间：2026-07-08
> 代号：R3-D / R3-D-storage
> 性质：本文是为 R3-D 实现补齐 Android 平台知识的 spike。§7 推荐汇总表**已被 `r3-local-reader-plan.md` R3-D 采纳全表**（2026-07-08 决策记录）；本文保留为调研输入与实现参考，不再作为待拍板方案阅读。`work-bundle-format.md` 的 B1–B9 决议基线不在本文变更范围。
> 上游：
> - [`r3-d-local-import-research.md`](r3-d-local-import-research.md) §6.6 / §6.8 / §7.4（问题全景与待补技术信息清单）
> - [`r3-local-reader-plan.md`](r3-local-reader-plan.md) §2.5（`LocalLibraryEntry`）/ §2.7（本地提交模型）
> - 主仓库 [`docs/knowledge/architecture/work-bundle-format.md`](../../../../../../docs/knowledge/architecture/work-bundle-format.md)（`.kmdwork` 容器决议基线 B1–B9）

## 0. 钉死的约束（不在本文重开讨论）

| # | 约束 | 来源 |
|---|---|---|
| C1 | `.kmdwork` 是 zip 单文件容器，仅宿主导入/导出用；runtime 热路径只消费 `work + source/sourceUrl/assetManifest + settings`，不吃 zip | B1/B2，调研稿 §6.6 |
| C2 | 外部原始文件不作长期唯一副本；展开目录是可重建 cache；长期归宿是应用私有 bundle store；Room 只存轻量索引 | B9，调研稿 §6.8 |
| C3 | 打包/解包器自实现（未来手机端 editor 复用） | B9 |
| C4 | `LocalLibraryEntry` 不扩成第二个 `Work`：可加指针/快照，不能内联 revision/asset 全量 | 调研稿 §6.5 / §6.8 |
| C5 | 本地身份 = bundleId（UUID，跨设备稳定）；contentHash 只作内容寻址/去重 | B8 |

## 1. 现状核实（2026-07-08 仓库状态）

本节是核实后的事实，作为后续方案对比的地面真值。代码引用以 `apps/android-reader/` 为根。

### 1.1 ReaderLoadRequest 与 runtime 加载链路

`ReaderLoadRequest` 定义在 `app/src/main/java/com/example/kmd_reader/runtime/ReaderRuntimeModels.kt:5-11`：

```kotlin
data class ReaderLoadRequest(
    val work: Work,
    val source: String? = null,
    val sourceUrl: String? = null,
    val assetManifest: ReaderRuntimeAssetManifest? = null,
    val settings: ReaderSettings = ReaderSettings()
)
```

`ReaderRuntimeAssetManifest`（同文件 `:53-57`）含 `baseUrl: String?` + `fonts` + `assets: Map<String, ReaderRuntimeAssetRef>`。生产加载链路在 `KmdReaderViewModel.kt:188-231`，构造时 **`sourceUrl` 永远不传**（默认 null），`source` 来自 `repository.getWorkSource(workId)`（纯文本 `String?`，非文件 URI），`assetManifest` 来自 `work.assetManifest?.toReaderRuntimeAssetManifest()`（`ReaderRuntimeMappers.kt:5-14`，注意该 mapper **丢弃 `fonts`**，因为 domain `WorkAssetManifest` 无 `fonts` 字段）。

`settings.assetBaseUrl` 默认 `"./"`（`ReaderRuntimeModels.kt:13-19`），即 runtime 解析 asset 相对路径时基准是 WebView 自身加载 URL。

编码侧 `RuntimeMessageCodec.kt:31-46` 的 `encodeLoadScript`：`source`/`sourceUrl`/`assetManifest` 都是 `?.let` 条件写入。另有一条**嵌套的** `sourceUrl` 通道在 `work.script.activeRevision.sourceUrl`（`:156`），mock 作品是 `"/works/$workId/source"`（`MockWorks.kt:215`），与顶层 `request.sourceUrl` 是两条独立通道。

### 1.2 KmdSourceMetadataParser

`app/src/main/java/com/example/kmd_reader/domain/kmd/KmdSourceMetadataParser.kt:6-57`。只解析 frontmatter 的 `mode` / `designWidth` / `designHeight` 三个字段。`mode` 处理（`:45-53`）：`scroll` / `page`+`paged` / `stage` / `interactive` 五种值，case-insensitive，未识别返回 null。不解析 `title` / `author` / `speed` / `var:` / `bgColor` 等（与调研稿 §3.1 / §6.3 盘点一致）。

### 1.3 ImportDesk 与 SAF

`ImportDesk.kt:16-36` 纯 mock：一个按钮调 `onMockImport("choice-room")`，UI 文案明写"第二阶段先验证流程，不触发真实文件选择器"。接线 `KmdReaderApp.kt:225-227` 把它映射到 `dispatch(KmdReaderAction.OpenWork(it))`。`KmdReaderEffect.OpenImportPicker`（`KmdReaderEffect.kt:6`）被 ViewModel 发出（`KmdReaderViewModel.kt:120-123`），但 `KmdReaderApp.kt:67` 的 collector 是 **no-op `Unit`**。

`app/src/main` 全树搜 `rememberLauncherForActivityResult` / `ActivityResultContracts` / `OpenDocument` / `ACTION_OPEN_DOCUMENT` / `takePersistableUriPermission` / `StorageAccess` —— **零命中**。SAF 完全未接线。

### 1.4 LocalLibraryEntry / Entity / DAO

Domain `LocalLibraryEntry`（`LocalLibraryRepository.kt:15-31`）与 Room `LocalLibraryEntity`（`LocalLibraryEntity.kt:11-28`）字段一致：`workId` (PK) / `source: WorkSourceType` / `onShelf` / `title` / `authorName` / `presentationMode` / `aspectRatio` / `kmdSource: String?` / `contentUri: String`（NOT NULL）/ `readingProgress` / `readingTimeMs` / `readingDurationMs` / `lastReadAt` / `importedAt` / `cachedAt`。

DAO（`LocalLibraryDao.kt:7-30`）用 `@Upsert`（不是 `@Insert(REPLACE)`，避免级联删子表，注释在 `:14-19`）。`updateProgress` / `setOnShelf` 走 get-then-copy-then-upsert，entry 不存在则空操作（`RoomLocalLibraryRepository.kt:104-120`）。

**关键空位**：`toLocalLibraryEntry()`（`KmdReaderViewModel.kt:1136-1153`）总是把 `kmdSource = null`、`contentUri = work.contentUri`（mock 值如 `"local/choice-room.kmd"`）。`kmdSource` 字段是 schema 占位，**生产路径从不写入**。

### 1.5 Room 数据库

`KmdReaderDatabase.kt`：`version = 3`（`:18`），`exportSchema = false`。实体：`WorkEntity` / `ScriptIssueEntity` / `LocalLibraryEntity` / `LocalRevisionEntity` / `LocalDraftEntity`。migration 列表只有 `MIGRATION_2_3`（`:103`），`fallbackToDestructiveMigration(dropAllTables = true)` 兜底。

### 1.6 WebView 与 asset 服务

**不用 `WebViewAssetLoader`**。自定义 host `kmd-reader-runtime.local`（`ReaderRuntimeHost.kt:48-52`），runtime URL = `https://kmd-reader-runtime.local/reader-runtime/index.html?v=...`（`:742-753`；实际落 `D0RuntimeAssetPath = "kmd-runtime/index.html"` fallback）。

`shouldInterceptRequest`（`:290-310`）**只拦截 host == `RuntimeAssetHost` 的请求**，其它 host 一律 `return null`（落入网络，对私有 host 会失败）。命中后 `openRuntimeAsset`（`:765-776`）走 `context.assets.open(path)`，即只服务 `assets/` 目录。`allowFileAccess = false` / `allowFileAccessFromFileURLs = false` / `allowUniversalAccessFromFileURLs = false` / `MIXED_CONTENT_NEVER_ALLOW`（`:181-366`）。

JS 桥：`window.KmdAndroid.postMessage(json)` → `RuntimeJavascriptBridge`；native→JS 调 `window.KmdRuntime.receive(json)`（`WebViewReaderRuntimeBridge.kt:249-272`）。`assetBaseUrl` 默认 `"./"`，即 runtime 把相对 asset 路径解析到 `https://kmd-reader-runtime.local/...`，再被 `shouldInterceptRequest` 接住。

### 1.7 zip 代码

`app/src` 全树搜 `ZipInputStream` / `ZipFile` / `ZipEntry` / `unzip` —— **零命中**。解压链路是 greenfield。

### 1.8 构建配置

`app/build.gradle.kts`：`minSdk = 28`（Android 9）/ `targetSdk = 36`（Android 16）/ `compileSdk = 36`。**未依赖 `androidx.webkit`**（`WebViewAssetLoader` 需要它）。

---

## 2. 问题 1：存储分层落地

### 2.1 三层分工方案对比

三层对应调研稿 §6.6 / §6.8：长期权威（bundle store）/ 导入来源记录 / 播放展开 cache。

| 方案 | filesDir | cacheDir | Room | 评价 |
|---|---|---|---|---|
| A. Room 存 entry source 文本 | — | — | `kmdSource` 全量文本 | 简单，纯 `.kmd` 快速；不适合多脚本/大资产；DB 膨胀；违背 C2"Room 只存轻量索引" |
| B. Room 存 zip/content Uri | — | — | `contentUri` = SAF Uri | 小而简单；SAF 权限/外部文件变动/长期可访问性不稳定；违背 C2"外部原件不作长期唯一副本" |
| C. filesDir = bundle store，cacheDir = 展开 cache，Room = 索引 | 长期权威 | 可重建 cache | 指针 | 对齐 C2；要实现 pack/unpack + 清理 + 迁移 |
| D. filesDir 同时存 bundle 和展开目录 | 长期权威 + cache | 不用 | 指针 | cache 不会被 OS 清，但要自己管清理；cache 体积进 filesDir 会吃 auto-backup 25MB 配额 |

### 2.2 平台事实（核实）

- `filesDir`：app 私有，无需权限；OS 不主动清（仅 uninstall / 用户 clear-data 删除）；默认纳入 auto-backup。来源：[app-specific storage](https://developer.android.com/training/data-storage/app-specific)。
- `cacheDir`：app 私有，无需权限；**OS 在存储压力下可主动清**；**默认不纳入 auto-backup**。来源同上 + [autobackup](https://developer.android.com/guide/topics/data/autobackup)（`getCacheDir()` 在排除列表）。
- auto-backup **每用户每 app 25MB 上限**（来源 autobackup 页），超过 `onQuotaExceeded` 跳过备份。`fullBackupContent` / `dataExtractionRules` 可精细排除。
- Room 官方角色定位是"结构化数据"，文件系统承载大文件/blobs（来源 [data-storage](https://developer.android.com/training/data-storage)）。官方未写"Room 不存 blob"的明文，但角色分工隐含此结论。

### 2.3 推荐

**推荐方案 C**，目录布局如下（`<bundleId>` 是 C5 的 UUID）：

```text
filesDir/
  bundles/
    <bundleId>/
      work.json                      # bundle manifest（轻量，可备份）
      scripts/main.kmd               # entry source（长期权威副本）
      assets/...                     # asset 字节（长期权威副本）
      revisions/<revId>.kmd          # 全量 snapshot（R3 阶段，§5）
      original.kmdwork               # 可选：导入原件备份（可重建，可清）
cacheDir/
  runtime-extract/
    <bundleId>/                      # 当前播放的展开 cache
      scripts/main.kmd               # 符号链接或副本
      assets/...                     # 供 WebView 读取
```

理由：
1. `filesDir/bundles/` 是长期权威，OS 不清，跨升级存活，对齐 C2。
2. `cacheDir/runtime-extract/` 是播放 cache，OS 压力下可清，重建成本 = 解 zip；对齐调研稿 §6.8"移动端一次通常只播放一个作品"。
3. `original.kmdwork` 作为**可选备份**放在 filesDir 而非依赖外部 SAF Uri，满足 C2"不作唯一副本"但留一条灾难恢复路径；空间紧张时可清（manifest + scripts/assets 足以重建 zip）。
4. 不把展开 cache 放进 filesDir（方案 D 的代价）：会吃 backup 配额且需自管清理；cacheDir 的 OS 清理语义正好契合"可重建"。

### 2.4 清理策略

| 触发 | 动作 | 理由 |
|---|---|---|
| 导入新作品 | 写 `filesDir/bundles/<bundleId>/`；清 `cacheDir/runtime-extract/` 旧 cache | cache 单作品假设 |
| 播放结束 / ViewModel onCleared | 保留 cache（可能很快再播）；不主动清 | cacheDir 由 OS 管 |
| 用户删作品（书架移除） | 删 `filesDir/bundles/<bundleId>/` + `cacheDir/runtime-extract/<bundleId>/` + Room 行 | 用户语义 = 不再保留 |
| 低存储（`onTrimMemory(TRIM_MEMORY_RUNNING_LOW)`） | 清 `cacheDir/runtime-extract/` 全部 | cache 可重建 |
| 启动时 | 扫 `cacheDir/runtime-extract/`，删 Room 索引里不存在的孤儿 | cacheDir 可能被 OS 清过，但反向孤儿也要处理 |

### 2.5 Room 索引指针字段（对齐 C4）

`LocalLibraryEntity` **增加指针字段，不内联全量**：

| 字段 | 类型 | 语义 | 是否 C4 允许 |
|---|---|---|---|
| `bundleId` | String? | 指向 `filesDir/bundles/<bundleId>/`；本地导入必填，社区/mock 为 null | 指针 ✓ |
| `bundleManifestPath` | String? | `filesDir/bundles/<bundleId>/work.json` 相对路径；快照读取用 | 指针 ✓ |
| `activeRevisionId` | String? | 指向 `local_revisions` 最新提交；对齐调研稿 §6.5 缺口 | 指针 ✓ |
| `contentHash` | String? | 最新提交的 contentHash；去重/快速比对 | 快照 ✓ |
| `originWorkId` | String? | 云端 workId（origin mapping，不作本地身份，B8） | 指针 ✓ |
| `assetManifestRef` | String? | bundle 内 assetManifest 的引用（实际读 `work.json`），可省 | 指针（冗余） |

**不进 `LocalLibraryEntry`**（C4 红线）：revision 列表、asset 字节、完整 manifest JSON、source 全文。`kmdSource: String?` 保留作为**裸 `.kmd` 无资产作品的 MVP 快捷路径**（对齐 `r3-local-reader-plan.md` §2.3/§2.5），带 assets 的作品 `kmdSource` 留 null，source 从 bundle store 读。

### 2.6 仍开放

- `original.kmdwork` 备份是否默认开启、还是设置项、还是不存（空间 vs 灾难恢复权衡）。
- `filesDir/bundles/<bundleId>/revisions/` 是否在 R3 就建，还是 R3-E 本地提交切片才建（与 `r3-local-reader-plan.md` §2.7 的 R3-E 范围对齐）。
- 用户 clear-data 后 bundle store 全丢是否可接受（auto-backup 25MB 配额是否要显式排除 `bundles/` 的 asset 字节，只备份 manifest + scripts）。

---

## 3. 问题 2：SAF 策略

### 3.1 方案对比：复制进私有目录 vs persistable Uri permission

| 方案 | 流程 | 优点 | 风险/代价 |
|---|---|---|---|
| A. persistable Uri permission，不复制 | `takePersistableUriPermission` 后每次播放从 SAF Uri 流式读 | 不占私有空间；"原件即权威" | 外部文件被删/移/改 → 播放断；SAF 流式读 zip 慢且不能随机访问；违背 C2"外部原件不作长期唯一副本" |
| B. 导入时复制进私有目录 | 导入时 `contentResolver.openInputStream(uri).copyTo(filesDir/...)` | 可控、离线稳定、对齐 C2；后续不再依赖 SAF 权限 | 占私有空间；要实现复制 + 校验 |
| C. A + B 混合：复制为主，Uri 作辅助记录 | 复制进 bundle store；`LocalLibraryEntry.contentUri` 存原始 SAF Uri 作"导入来源记录" | 长期权威 = 复制副本；Uri 仅用于"重新导入/打开原位置"等可选功能 | Uri 可能失效，要用 try-catch 兜底 |

### 3.2 平台事实（核实）

- `ACTION_OPEN_DOCUMENT` **默认带 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`**（来源 [Intent.ACTION_OPEN_DOCUMENT](https://developer.android.com/reference/android/content/Intent#ACTION_OPEN_DOCUMENT)），调用方不必加 flag。
- `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` 跨重启保留（来源 [shared/documents-files](https://developer.android.com/training/data-storage/shared/documents-files)）。**但文档明确：document 被 move/delete 后访问丢失**。
- `ACTION_GET_CONTENT` 不授 persistable（短期 import 语义）；要长期访问必须用 `ACTION_OPEN_DOCUMENT`。
- `persistedUriPermissions` 可列出当前持久权限；用户可在系统设置撤销。

### 3.3 MIME 选择

`.kmdwork` 是 zip，但 SAF **按 MIME 而非扩展名过滤**（来源同上 reference）。现实问题：部分 provider 把 zip 报成 `application/octet-stream`。

| 方案 | 写法 | 评价 |
|---|---|---|
| A. `type = "application/zip"` | 简单 | 部分.provider 报 octet-stream 的文件选不到 |
| B. `type = "*/*"` + `EXTRA_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed")` | 官方推荐组合 | 仍可能漏 octet-stream |
| C. `type = "*/*"` + 导入后读文件头校验（PK\x03\x04 magic） | 最宽松 | 把 MIME 责任从 picker 移到 app；推荐 |
| D. 同时支持 `.kmd`（纯文本） | `EXTRA_MIME_TYPES = arrayOf("text/plain", "text/markdown", "application/zip", "application/x-zip-compressed")` | 兼容裸 `.kmd` 导入（R3-D MVP 路径） |

### 3.4 推荐

**推荐 3.1-C + 3.3-D**：
1. 导入时**复制进 `filesDir/bundles/`**（方案 B 的复制动作 + C 的 Uri 记录）。复制后立即跑 zip 安全校验（§5）+ contentHash。
2. **不调 `takePersistableUriPermission`**。复制完成后 SAF Uri 即可弃；`LocalLibraryEntry.contentUri` 仍存原始 SAF Uri 字符串，仅作"导入来源记录"和未来"重新从原位置导入"的可选入口（访问前 try-catch，失效则提示"原文件不可访问，使用应用内副本"）。
3. SAF picker 用 `type = "*/*"` + `EXTRA_MIME_TYPES`（zip + text/markdown），导入后读 magic bytes 判 zip vs 纯文本。

### 3.5 用户事后删除/修改原文件时

| 情况 | 应用行为 |
|---|---|
| 用户删除原文件 | 应用私有副本仍在，正常播放；`contentUri` 访问失败时 UI 不崩（复制后不依赖它） |
| 用户修改原文件 | 应用私有副本是导入时快照，**不自动同步**；要"重新导入"需用户主动点导入按钮 |
| 用户 move 原文件 | `contentUri` 可能失效（document-provider 语义），私有副本正常 |

这条对齐 C2"外部原件不作长期唯一副本"——应用从导入那一刻起就接管权威。

### 3.6 仍开放

- 是否提供"监听原文件变化自动提示重新导入"（`ContentResolver.registerObserver` 或 `takePersistableUriPermission` 后定期 probe）——R3 MVP 不做。
- `.kmd` 纯文本导入是否走同一 zip 展开路径（伪 zip：单文件直接当 `scripts/main.kmd` 存），还是保留 `r3-local-reader-plan.md` §2.3 的 `kmdSource` 全文快捷路径——倾向两者共存：纯文本走 `kmdSource`，`.kmdwork` 走 bundle store。

---

## 4. 问题 3：WebView asset 映射

### 4.1 方案对比

展开后的 assets 在 `cacheDir/runtime-extract/<bundleId>/assets/`，WebView 要能读。`assetManifest` 内 URL 是 bundle 内相对路径（`work-bundle-format.md` §3），要映射到 WebView 可安全读的路径。

| 方案 | 机制 | 优点 | 缺点 |
|---|---|---|---|
| A. `WebViewAssetLoader` + `InternalStoragePathHandler` | androidx.webkit，注册 `https://appassets.androidplatform.net/assets/...` → `filesDir` | 官方推荐；HTTPS/CORS/secure origin 自动对；`InternalStoragePathHandler` 可服务 `filesDir` | 服务 `cacheDir` 需自定义 `PathHandler`；要加 `androidx.webkit` 依赖；现有 `kmd-reader-runtime.local` host 要共存 |
| B. 扩展现有 `shouldInterceptRequest` 拦一个新 host | 新增 host 如 `kmd-reader-assets.local`，拦截后从 `cacheDir/runtime-extract/<bundleId>/` 读 | 复用现有模式，零新依赖 | 自管 MIME/encoding/cache header；XHR/fetch 边界 case 要自己处理；secure origin 要靠 host 是 `https://` |
| C. `file://` + `allowFileAccess = true` | 直接读 `cacheDir` | 零拦截 | `targetSdk 30+` 默认 `allowFileAccess = false`（见 §4.2）；CORS/mixed-content 一堆问题；**不推荐** |

### 4.2 平台事实（核实）

- `WebSettings.setAllowFileAccess` 默认 **target API 30+ 为 `false`**（来源 [WebSettings](https://developer.android.com/reference/android/webkit/WebSettings#setAllowFileAccess(boolean))）。本项目 `targetSdk = 36`，默认 false。`allowFileAccessFromFileURLs` / `allowUniversalAccessFromFileURLs` 自 API 16 默认 false。
- `WebViewAssetLoader` 默认 host `https://appassets.androidplatform.net/`（来源 [WebViewAssetLoader](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)）；`InternalStoragePathHandler` 服务 `getFilesDir()`；自定义 `PathHandler` 可服务任意路径（含 `cacheDir`）；与 `shouldInterceptRequest` **共存**（assetLoader 本身就在 `shouldInterceptRequest` 里调）。
- 现状（§1.6）已用自定义 host + `shouldInterceptRequest`，未依赖 `androidx.webkit`。

### 4.3 推荐

**推荐方案 A（`WebViewAssetLoader`）+ 现有 host 共存**，但分两步：

**第一步（最小改动，R3-D MVP）**：扩展现有 `shouldInterceptRequest`（`ReaderRuntimeHost.kt:290-310`），新增一个 asset host，例如 `kmd-reader-assets.local`，命中后从 `cacheDir/runtime-extract/<bundleId>/` 解析路径。理由：R3-D MVP 只需读当前播放作品的 assets，不引入新依赖，复用现有 MIME 猜测逻辑（`mimeTypeForAsset` `:811-826`）。

**第二步（R3 后稳定化）**：迁移到 `WebViewAssetLoader` + 自定义 `PathHandler`，把 runtime host 和 asset host 统一进 assetLoader 框架，获得 secure origin / CORS 的官方保障。届时加 `androidx.webkit` 依赖。

### 4.4 路径映射规则

bundle manifest 内 URL = bundle 相对路径（如 `assets/fonts/my-font.woff2`）。映射到播放虚拟 HTTPS 路径：

| manifest 内 URL | 播放路径（方案 B 第一步） | 文件系统落点 |
|---|---|---|
| `assets/fonts/my-font.woff2` | `https://kmd-reader-assets.local/<bundleId>/assets/fonts/my-font.woff2` | `cacheDir/runtime-extract/<bundleId>/assets/fonts/my-font.woff2` |
| `scripts/main.kmd`（如 runtime 要 fetch） | `https://kmd-reader-assets.local/<bundleId>/scripts/main.kmd` | 同上 |

`assetManifest.baseUrl` 设为 `https://kmd-reader-assets.local/<bundleId>/`，runtime 解析相对 asset 路径时基准就是这个虚拟 host，请求被 `shouldInterceptRequest` 接住。

### 4.5 与现有 ReaderLoadRequest 的接线

现有 `ReaderLoadRequest`（§1.1）已支持 `source` + `sourceUrl` + `assetManifest`。本地导入作品的加载链路改造点：

1. `repository.getWorkSource(workId)` 增加分支：若 `LocalLibraryEntry.bundleId != null`，从 `filesDir/bundles/<bundleId>/scripts/main.kmd` 读 source（或读最新 revision snapshot，对齐 `r3-local-reader-plan.md` §2.7 播放优先级）。
2. 构造 `ReaderLoadRequest` 时：
   - `source` = 从 bundle store 读的 entry `.kmd` 全文。
   - `assetManifest` = 从 `work.json` 读出的 `assetManifest`，**`baseUrl` 改写为** `https://kmd-reader-assets.local/<bundleId>/`（展开层职责，见 `work-bundle-format.md` §4）。
   - `sourceUrl` 仍可不传（runtime 用 `source` 文本播放；`sourceUrl` 留给 runtime 需要重新 fetch source 的场景，本地导入不需要）。
3. 展开层在 load 前 ensure `cacheDir/runtime-extract/<bundleId>/` 存在（从 `filesDir/bundles/` 解压或符号链接 assets）。

接线点文件：`KmdReaderViewModel.kt:188-231`（构造 `ReaderLoadRequest` 处）、`ReaderRuntimeMappers.kt:5-14`（`toReaderRuntimeAssetManifest`，要补 `baseUrl` 改写）、`ReaderRuntimeHost.kt:290-310`（`shouldInterceptRequest` 扩展）。

### 4.6 仍开放

- ~~第一步是否够：`shouldInterceptRequest` 对 `fetch()` / XHR / `new FontFace()` 的边界 case 是否全覆盖~~——**已核实关闭（2026-07-08，主仓库）**：runtime 字体全部经原生 FontFace API 加载（`core/App.ts:290-298`），URL 由 `RuntimeAssetPolicy.resolveRuntimeAssetUrl` 按 `assetManifest.baseUrl` 解析，FontFace `url()` 请求走 WebView 资源加载管线、可被 `shouldInterceptRequest` 拦截（现有随包字体正是经 `kmd-reader-runtime.local` 这样加载的）；`assetManifest.assets`（图片/shader/audio）为契约占位、core 无消费点，第一步无需覆盖。注意点：Android 侧 `toReaderRuntimeAssetManifest` 丢弃 `fonts`（§1.1），bundle 字体透传需在播放接线时补齐。详见 `r3-local-reader-plan.md` §R3-D4 与"主仓库核实结论"。
- `assetManifest.baseUrl` 改写是放在 mapper 层（`ReaderRuntimeMappers`）还是展开层（导入时持久化改写过的 manifest）——倾向展开层持久化，避免每次播放重算。
- 多脚本场景（`work-bundle-format.md` §6 开放项）下 `scripts/` 也要进虚拟 host，路径规则要预留。

---

## 5. 问题 4：zip 安全

### 5.1 平台事实（核实）

- **`ZipPathValidator`（API 30+/Android 11+）**：`ZipFile` / `ZipInputStream` 自动校验 entry 路径，对穿越序列抛 `IllegalArgumentException`（来源 [ZipFile](https://developer.android.com/reference/java/util/zip/ZipFile)）。本项目 `minSdk = 28`，**API 28/29 设备无此保护**，需手动兜底。
- `ZipInputStream` 流式，适合 untrusted 流，无随机访问；`ZipFile` 走中央目录，适合先 count entries / 校验再解。
- zip slip 经典手动防御：reject `entry.name` 含 `..` 逃逸段，并 `File(targetDir, entryName).canonicalPath.startsWith(targetDir.canonicalPath + File.separator)`。官方未明文写此 snippet，但是社区标准。

### 5.2 防护清单（Kotlin 侧）

| # | 威胁 | 防护 | 实现要点 |
|---|---|---|---|
| Z1 | zip slip（路径穿越） | API 30+ 靠 `ZipPathValidator`；API 28/29 手动 `canonicalPath.startsWith` | `if (!File(targetDir, entry.name).canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) throw` |
| Z2 | 解压炸弹（总大小爆炸） | 预设总解压上限，累计字节超限即停 | `maxTotalUncompressedBytes`（建议 50MB，按 KMD 作品规模调）；累加 `entry.size`，超限抛 + 清理半解文件 |
| Z3 | 单条目超大 | 预设单 entry 上限 | `maxEntryBytes`（建议 10MB）；`entry.size > maxEntryBytes` 抛 |
| Z4 | 条目数爆炸 | 预设条目数上限 | `maxEntries`（建议 1024）；count 超限抛 |
| Z5 | hash 校验 | 导入完成后对 entry `.kmd` 算 SHA-256，与 manifest `revisions[].contentHash` 比对 | 不符则拒绝导入（manifest 是权威，§`work-bundle-format.md` §3） |
| Z6 | 重复路径 | zip 内同名 entry，`ZipInputStream` 会按顺序覆盖；检测重复并拒 | `Set<String>` 记录已写路径，重复抛 |
| Z7 | 文件名编码 | zip 文件名编码非 UTF-8 时 `ZipEntry.name` 乱码 | 解压前读 zip 的 EFS 标志位；非 UTF-8 用 `Charset.forName("GBK"/"Shift_JIS")` 兜底（KMD 作品中日文/中文作者常见） |
| Z8 | manifest 校验 | 解压后校验 `work.json`：`formatVersion`、`entry` 指向的脚本存在、`assetManifest` 引用的 asset 都在 zip 内（引用闭合） | 引用不闭合 = bundle 损坏，拒绝导入 |
| Z9 | 中央目录攻击（`ZipFile` 特有） | 优先用 `ZipInputStream` 流式解 untrusted zip；若用 `ZipFile` 先 `size()` 查条目数，超 `maxEntries` 直接拒 | `ZipFile(inputStream).use { if (it.size() > maxEntries) throw }` |

### 5.3 参考实现要点（不写生产代码，只给骨架）

```kotlin
// 流式解压，untrusted zip
fun extractKmdwork(
    input: InputStream,
    targetDir: File,
    maxTotalUncompressed: Long = 50L * 1024 * 1024,
    maxEntryBytes: Long = 10L * 1024 * 1024,
    maxEntries: Int = 1024,
): ExtractResult {
    val seen = mutableSetOf<String>()
    var total = 0L
    var count = 0
    ZipInputStream(input).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            try {
                if (entry.isDirectory) continue
                count++.also { if (count > maxEntries) throw SecurityException("too many entries") }
                // Z1: API 28/29 手动 zip slip（API 30+ ZipPathValidator 已挡）
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator))
                    throw SecurityException("zip slip: ${entry.name}")
                // Z6: 重复路径
                if (!seen.add(outFile.canonicalPath)) throw SecurityException("dup path: ${entry.name}")
                // Z2/Z3: 大小上限
                if (entry.size > maxEntryBytes) throw SecurityException("entry too big: ${entry.name}")
                total += entry.size
                if (total > maxTotalUncompressed) throw SecurityException("total too big")
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { zis.copyTo(it) }
            } finally {
                zis.closeEntry()
            }
        }
    }
    return ExtractResult(...)
}
```

注意：`entry.size` 对未压缩大小可靠（来自 zip local header）；若要更严格，边写边累加实际字节，防止 header 撒谎。

### 5.4 推荐

- 用 `ZipInputStream` 流式解 untrusted `.kmdwork`（不先用 `ZipFile` 读中央目录，避免内存压力）。
- API 28/29 手动 zip slip 防御（Z1）；API 30+ `ZipPathValidator` 是 defense-in-depth。
- 全部 Z1–Z9 清单纳入首版 unpacker；上限值（50MB/10MB/1024 entries）作为常量，按实际作品规模调。
- hash 校验（Z5）放在解压完成后、写入 `LocalLibraryEntry` 前。

### 5.5 仍开放

- zip 文件名编码（Z7）是否 R3 就处理，还是等首条非 UTF-8 作品出现再补——倾向首版就加 EFS 检测，代价小。
- 重复路径（Z6）是拒绝还是后写覆盖——倾向拒绝（更安全，且 `.kmdwork` 由 packer 生成不应有重复）。

---

## 6. 问题 5：revision 存储规模

### 6.1 量级估算

`r3-local-reader-plan.md` §2.7 R3 范围：全量 snapshot，每个 `LocalRevision.source` = 完整 `.kmd` 源文本快照。按题目假设：

| 作品规模 | 单 `.kmd` 大小 | 提交数 | 总 snapshot 体积 |
|---|---|---|---|
| 小（短篇/动效字幕） | 10 KB | 10 | 100 KB |
| 中 | 30 KB | 30 | 900 KB |
| 大（长叙事，纯文本） | 100 KB | 50 | 5 MB |
| 大 + 多脚本（未来） | 100 KB × N 脚本 | 50 | 5 MB × N |

Room 存 vs 文件系统存：
- Room 内联（`LocalRevisionEntity.source: String`）：单作品最坏 5MB 进 DB；Room 单行 blob 上限 1MB（SQLite BLOB 常见限制，但 SQLite 实际支持到 ~1GB），5MB 会跨多行或触发 cursor 限制。**不推荐 Room 内联**。
- 文件系统 + Room 指针（对齐 §2.3：`sourcePath` 是 filesDir 下相对路径；bundle 作品可落 `bundles/<bundleId>/revisions/<revId>.kmd`，裸 `.kmd` 落独立 local revision 根）：Room 只存 `LocalRevision` 索引（`id`/`parentRevisionId`/`contentHash`/`message`/`syncState`/`remoteRevisionId`/`createdAt` + `sourcePath` 指针），source 全文落文件系统。

### 6.2 量化阈值建议

| 总 snapshot 体积（单作品） | 是否成问题 | 建议 |
|---|---|---|
| < 1 MB | 否 | 全量 snapshot，无脑存 |
| 1–10 MB | 边界 | 仍全量 snapshot，但监控；考虑压缩存储（gzip） |
| > 10 MB | 是 | 引入 diff 模型：存 base snapshot + 后续 revision 存 unified diff（或 Rabin-Karp chunk delta） |
| > 50 MB 或提交 > 100 | 必须diff | 全量 snapshot 在移动端磁盘/IO 上不可接受 |

### 6.3 何时需要 diff 模型

阈值触发条件（任一满足即应评估 diff）：
1. **单作品 revision snapshot 总体积 > 10 MB**（磁盘/备份压力）。
2. **单作品提交数 > 100**（列表加载/列表 UI 性能）。
3. **多脚本落地后，单 revision 含多文件且总大小 > 200 KB**（snapshot 复制成本随脚本数线性增长）。
4. **auto-backup 25MB 配额被 bundle store + revision snapshot 占满**（`onQuotaExceeded` 触发，备份失败）。

### 6.4 推荐

- **R3 全量 snapshot**，source 落 filesDir 下、由 Room `LocalRevisionEntity.sourcePath: String` 保存相对指针，**不内联 source 文本**。`.kmdwork` / `bundleId != null` 使用 `bundles/<bundleId>/revisions/<revId>.kmd`；裸 `.kmd` / `bundleId == null` 使用 `local-revisions/<workKey>/revisions/<revId>.kmd`，`workKey` 必须是由 `workId` 派生的安全单路径段（hash/UUID 均可）。
- 在 `LocalRevisionEntity` 预留 `storageMode: String` 字段（`"full"` / `"diff"`），R3 恒 `"full"`，未来切 diff 不破坏 schema。
- 阈值监控：导入/提交时累计单作品 revision 总体积，超 10MB 记 warning log（不阻塞），供后续决策。
- diff 模型的具体格式（unified diff / chunk delta / git packfile 风格）**不在 R3 定**，留 `work-bundle-format.md` §6 开放项与 R3 后 revision store 切片。

### 6.5 仍开放

- diff 模型的 patch 格式（unified diff 对文本友好，但对 KMD 内联命令序列的语义 diff 是否更合适）。
- revision snapshot 是否压缩存储（gzip 10KB 文本压到 ~3KB，但随机读要解压）。
- 多脚本 revision 是 whole-bundle commit（单 revision 含多文件 snapshot）还是 per-file commit（`work-bundle-format.md` §6 开放项，影响体积估算）。

---

## 7. 推荐汇总（已被 R3 plan 采纳）

> **采纳状态（2026-07-08）**：本表全部推荐已被 `r3-local-reader-plan.md` R3-D 采纳（决策记录见该文 R3-D 引言）。

| 问题 | 推荐 | 仍开放要点 |
|---|---|---|
| 1. 存储分层 | 方案 C：`filesDir/bundles/` 长期权威 + `cacheDir/runtime-extract/` 播放 cache + Room 轻量索引；`LocalLibraryEntry` 加 `bundleId`/`activeRevisionId`/`contentHash`/`originWorkId` 指针，不内联全量 | `original.kmdwork` 备份策略；auto-backup 是否排除 asset 字节 |
| 2. SAF | 导入时复制进私有目录（方案 B+C），不调 `takePersistableUriPermission`；`contentUri` 仅作来源记录；MIME 用 `*/*` + `EXTRA_MIME_TYPES` + magic bytes 校验；支持 `.kmd` 与 `.kmdwork` 双格式 | 是否监听原文件变化提示重导入 |
| 3. WebView asset | R3-D MVP 扩展现有 `shouldInterceptRequest` 拦新 host `kmd-reader-assets.local`；后续迁移 `WebViewAssetLoader`；`assetManifest.baseUrl` 改写为 `https://kmd-reader-assets.local/<bundleId>/`，展开层持久化改写过的 manifest | `fetch`/`FontFace` 边界 case 覆盖核实；多脚本 `scripts/` 路径预留 |
| 4. zip 安全 | `ZipInputStream` 流式 + Z1–Z9 防护清单；API 28/29 手动 zip slip，API 30+ `ZipPathValidator` defense-in-depth；上限 50MB/10MB/1024 entries | zip 文件名编码（Z7）首版是否处理 |
| 5. revision 规模 | R3 全量 snapshot，source 落文件系统 + Room 指针；阈值 >10MB 或 >100 提交触发 diff 评估；`storageMode` 字段预留 | diff 格式；snapshot 压缩；多脚本 commit 颗粒度 |

## 8. 本文的边界

本文**不做**：
- 不把推荐包装成最终规范——§7 推荐已被 `r3-local-reader-plan.md` R3-D 采纳全表，本文保留为调研输入与实现参考；后续实现以 R3 plan 为准。
- 不定义 `.kmdwork` manifest 字段细节（那是 `work-bundle-format.md` §6 开放项）。
- 不写生产代码（spike 性质）。

本文**要做的**是：把 §7.4 待补技术信息清单里的 1/2/3/4/6 项补齐平台事实与方案对比，让规划者能在一张表里看到每个问题的选项、约束和推荐，而不是每次讨论都要重新查 Android 文档和核实代码现状。
