# MyBooks 安卓端改造方案 v2：服务器书库直接融合进本地书架

## 一、目标（按最新要求修订）

把 `https://helloac.top`（my-books）上的书**直接变成 Lumi 书架里的书**，和本地导入的书混排在一起：

- 打开 App 后书架里就能看到服务器上的书（封面、书名、作者），带「云端」角标；
- 点封面弹出原有的「下载」弹窗 → 下载进度环 → 下载完成后**原地变成可阅读的本地书**，自动进入阅读器；
- 阅读进度、笔记、书签、统计**只存在本地**，不回传，不做进度同步；
- 不新增独立页面、不改数据库结构、不新增路由；上游 Lumi 更新时冲突点控制在 6 处。

上一版方案是「新增一个独立云端书库 Activity」，与现在的需求不符，本次改为**数据层融合**。

## 二、现状分析（全部已核实的代码事实）

### 2.1 上游已有「云端书」机制，可直接复用

Lumi 本身支持 WebDAV 云书库，云端书就是一条普通 `books` 记录，只是 `isCloudOnly = 1`、`filePath = ""`：

- [BookEntity.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/local/entity/BookEntity.kt) 已有 `isCloudOnly / remoteLibraryKey / remoteFileName / remoteFileSize / remoteFileSha256 / sourceUri` 字段，**无需迁移**；
- [BookDao.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/local/dao/BookDao.kt#L15-L19) `getAllBooks()` 不过滤 `isCloudOnly` → 云端书天然出现在书架；
- [BookDao.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/local/dao/BookDao.kt#L52-L56) `insertBook` 是 `@Upsert`（整行替换）→ **更新已有书必须用 `updateBookMetadata`，不能 upsert**，否则会清掉阅读进度；
- [BookDao.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/local/dao/BookDao.kt#L67-L71) `markBookCloudOnly` / `markBookDownloaded(bookId, filePath)`：下载完成只需把 `isCloudOnly` 置 0 并写入 filePath；
- [CloudBookUi.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/components/CloudBookUi.kt#L144-L176) `BookCoverStatusBadge` 按 `book.isCloudOnly` 显示云端下载角标；
- [NavGraph.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/navigation/NavGraph.kt#L539-L557) 点云端书：`pendingCloudBook = book` 弹下载弹窗；下载中再点则 `autoOpenDownloadedBookId` 自动跳阅读器；
- [NavGraph.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/navigation/NavGraph.kt#L1400-L1407) 弹窗确认 → `homeViewModel.downloadCloudBook(book.id)`；
- [HomeScreen.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeScreen.kt#L231-L258) 卡片把 `downloadStates` 画成进度环；
- 已有「书籍详情」页 [BookDetailsActivity.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookDetailsActivity.kt#L220-L349)：封面/书名/作者/进度/时长/笔记/书签/文件大小/文件夹/标签/存储状态/导入时间/最后阅读/路径，且 `InfoCard` 已能显示「仅在云端」（[L399-L407](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookDetailsActivity.kt#L399-L407)），`fileSize()` 优先用 `remoteFileSize`（[L576-L580](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookDetailsActivity.kt#L576-L580)）——**详情页对云端占位书本来就不会崩，只缺"服务器元数据"和"下载按钮"**。

结论：**只要把服务器上的书写进 `books` 表并让 `downloadCloudBook` 认识它们，书架/卡片/下载弹窗/阅读器全自动复用；详情页只需追加一张图书信息卡**。

### 2.2 需要接入的上游点

| 位置 | 现状 | 需要的行为 |
|---|---|---|
| [HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L202-L216) `init` | 加载本地数据 | 追加一次服务器书库同步（节流） |
| [HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L483-L494) `loadWebdavSyncStatus` | 只收集 WebDAV 的 `downloadStates` | 改为 `combine(webdav, mybooks)` 合并进度 |
| [HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L508-L517) `downloadCloudBook` | 全部交给 `webdavSyncManager` | 按 `remoteLibraryKey` 前缀分流 |
| [HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L2089-L2113) `deleteBooks` | 云端删除全走 WebDAV | 过滤掉 `mybooks:` 的书 |
| [HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L250-L256) `syncedBookIds` | 过滤 `remoteFileName != null` | 不改（本书 `remoteFileName` 保持 null，见 3.3） |

### 2.3 my-books 接口（已读源码）

- `GET /api/books`：全量列表，含 `id/title/author/status/tags/cover/series_label/created_at/updated_at`，**不含 files**（[routes.books.js](file:///C:/littleBIngbing/my-books/src/http/routes.books.js#L90-L103)）；
- `GET /api/books/:id`：详情，含 `files: [{format, path, size}]`（`path` 为相对文件名）；
- 封面：`cover` = `/uploads/xxx`，静态托管（[server.js](file:///C:/littleBIngbing/my-books/server.js#L53)）；
- 正文：`/books-files/<path>` 静态托管（[server.js](file:///C:/littleBIngbing/my-books/server.js#L55)）；
- 鉴权：`requireAdmin` 对 GET/HEAD/OPTIONS 一律放行（[middleware.auth.js](file:///C:/littleBIngbing/my-books/src/http/middleware.auth.js#L149)），客户端**只发 GET、不带令牌**。

### 2.4 上一轮已写入、需调整的代码

- 保留并改造：`RemoteBook.kt`、`MyBooksApiClient.kt`
- 删除：`RemoteLibraryActivity.kt`、`RemoteLibraryScreen.kt`、`RemoteLibraryViewModel.kt`、`RemoteLibraryPrefs.kt`
- 回退：[AndroidManifest.xml](file:///C:/littleBIngbing/mybooks/app/src/main/AndroidManifest.xml) 中新增的 Activity 节点
- 保留：[HomeScreen.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeScreen.kt) 头部云图标，但改为「立即同步服务器书库」

## 三、方案

### 3.1 数据映射：服务器书 → 本地 Book

| Book 字段 | 取值 | 说明 |
|---|---|---|
| `id` | `"mybooks-" + remoteId` | 稳定、可反解 remoteId、不会与 WebDAV 的 hex id 冲突 |
| `title` / `author` | 服务端字段，author 空则「未知作者」 | |
| `filePath` | `""`（占位）→ 下载后由 `markBookDownloaded` 写入 | |
| `coverPath` | 同步时把 `/uploads/...` 下载到 `getCoversDirectory()/mybooks_cover_{id}.jpg` | 复用 Coil 本地文件加载；失败则为 null（EPUB 下载后会自动补封面） |
| `format` | 首选文件的格式 | 预览角标/打开方式依赖它 |
| `readingProgress` | `0f`，已有记录则保持原值 | |
| `lastReadTime` | 新插入用 `now`，已有记录保持不变 | 避免每次同步打乱"最近阅读" |
| `isCloudOnly` | `true` → 下载后 `false` | |
| `remoteLibraryKey` | `"mybooks:helloac.top"` | 用于下载/删除分流 |
| `remoteFileName` | **null** | 关键决策，见 3.3 |
| `remoteFileSize` | 首选文件 size | 卡片可显示体积 |
| `sourceUri` | `"mybooks://book/{remoteId}"` | 去重键，`getBookBySourceUri` 已有 |
| `metadataUpdatedAt` | 同步时间 / 服务端 `updated_at` | 仅元数据更新用 |

不支持格式（AZW3 等）只在有可支持文件时入库；一本书一个格式，优先 `EPUB → PDF → MOBI → TXT → CBZ → MD`。

### 3.2 新增模块（全部落在新包，零上游代码改动）

`app/src/main/java/com/helloac/mybooks/remote/`

1. **`MyBooksConfig.kt`**
   ```kotlin
   const val SERVER_URL = "https://helloac.top"
   const val LIBRARY_KEY = "mybooks:helloac.top"
   const val BOOK_ID_PREFIX = "mybooks-"
   const val SOURCE_URI_PREFIX = "mybooks://book/"
   fun remoteIdOf(bookId: String): Long?
   fun libraryKeyOf(book: Book): Boolean
   ```

2. **`RemoteBook.kt`**（改造现有文件）
   - 保留 `RemoteBook/RemoteBookFile/RemoteBookFormats`
   - 增加 `parseRemoteBook(json, baseUrl)` 单本解析（详情复用）
   - 删除独立页面用的 UI 相关辅助（`statusFilter` 等留在 ViewModel 里已随删除消失）

3. **`MyBooksApiClient.kt`**（改造）
   ```kotlin
   suspend fun fetchBooks(): List<RemoteBook>            // GET /api/books
   suspend fun fetchBook(id: Long): RemoteBook           // GET /api/books/{id}
   suspend fun fetchCover(url: String, dest: File): Boolean
   suspend fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit)
   ```
   只发 GET；错误转成中文提示。

4. **`MyBooksSyncManager.kt`**（核心，`@Singleton`）
   ```kotlin
   val downloadStates: StateFlow<Map<String, BookDownloadState>>  // 复用上游类型
   suspend fun syncIfStale(maxAgeMs: Long = 10 * 60_000)
   suspend fun syncNow(): SyncResult
   suspend fun downloadBook(bookId: String): CloudBookDownloadResult
   ```
   `syncNow()` 逻辑：
   - `Mutex` 防并发；GET 列表；逐本 `getBookBySourceUri`：
     - 不存在 → 选首选文件 + 下封面 → `insertBook(Book(isCloudOnly = true, ...))`
     - 已存在且 `isCloudOnly` → 下封面（若远端换了封面）→ `updateBookMetadata(existing.copy(title, author, coverPath, format, remoteFileSize))`
     - 已存在且已下载 → 仅更新 title/author/coverPath，**不动 filePath/进度/时间**
   - 服务端删掉的书：本地仍保留（不自动删，避免误删本地已下载的书）；占位书可后续做"仅在服务端不存在时清理"的可选项，本轮不做。
   - 网络失败：不动本地数据，返回 Result.failure。

   `downloadBook(bookId)` 逻辑：
   - 反解 `remoteId` → `GET /api/books/{id}` 取最新 `files` → 选首选格式；
   - `.part` 流式下载到 `getBooksDirectory()/{bookId}.{ext}`，每 64KB 更新 `downloadStates`（`BookDownloadState.Downloading`）；
   - 成功后 `renameTo` 正式文件 → 若 `coverPath` 为空则 `BookParserFactory.createParser(...).extractCoverPath` 并写回；
   - `bookRepository.markBookDownloaded(bookId, filePath)`；失败清 `.part`，状态 `Failed(message)`；
   - 返回 `CloudBookDownloadResult(book, message, success)`（上游已有此类）。

5. **`MyBooksMetaCache.kt`**：`ServerBookMeta`（简介/标签/状态/评分/系列/files/更新时间）+ `filesDir/mybooks/meta/{remoteId}.json` 读写。

6. **`MyBooksBookInfoCard.kt`**：书籍详情页里的「服务器信息」Composable，见 3.4。

### 3.3 关键决策：`remoteFileName = null`

- 上游 `deleteBooks` 只对 `remoteFileName != null` 的书调用 WebDAV 删除（[HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L2089-L2113)），WebDAV 上传循环也只处理有 manifest 条目的云书（[WebdavSyncManager.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/WebdavSyncManager.kt#L202-L206)）。
- 设为 null 后：my-books 的书**永远不会**被 WebDAV 上传/删除逻辑碰到，也不会因 `LOCAL_ONLY` 删除留下"幽灵占位"。
- 代价：`syncedBookIds`（WebDAV"已同步"集合）不包含它们——但它们的云端角标来自 `isCloudOnly`，视觉不受影响。
- 额外保险：`deleteBooks` 里把 `mybooks:` 前缀的书从 `remoteIds` / `forceDelete` 的远端调用中过滤，防止 `FORCE_LOCAL_AND_CLOUD` 误走 WebDAV。

### 3.4 服务器图书详情在应用中的呈现

**结论：复用现有「书籍详情」页，不新建页面；在详情页追加一张「服务器信息」卡片，服务器上的书点按后直接进这张详情页，看简介/标签再决定下载。**

#### 3.4.1 元数据来自哪里

- 同步时把每本书的原始 JSON 存到 `filesDir/mybooks/meta/{remoteId}.json`（`MyBooksMetaCache`）；
- 打开详情页时后台再 `GET /api/books/{id}` 刷新（拿到 `files` 与最新简介），失败就用缓存；
- 下载完成后缓存保留，详情页永远能显示来源、简介、标签。

#### 3.4.1b 服务端详情字段 → 页面展示 全量映射（已对照服务端代码逐项核实）

服务端 `GET /api/books/:id`（[routes.books.js](file:///C:/littleBIngbing/my-books/src/http/routes.books.js#L30-L55) `withFiles` + [index.js](file:///C:/littleBIngbing/my-books/src/db/index.js#L41-L108) 建表/迁移）实际返回的全部字段：

| 服务端字段 | 类型/取值 | 安卓端显示位置 | 是否新增承载 |
|---|---|---|---|
| `id` | int | 仅用于本地去重键 `mybooks-{id}` | 否（不显示） |
| `title` | string | ① Hero 书名 | 复用 |
| `author` | string | ① Hero 作者 | 复用 |
| `status` | wish/reading/done | ④ 信息卡「阅读状态」chips（想读/在读/已读） | 新增 |
| `summary` | string（可长文本） | ④ 信息卡「简介」，4 行折叠 + 「展开/收起」 | 新增 |
| `tags` | JSON 数组字符串 | ④ 信息卡「标签」chips（水平流式换行） | 新增 |
| `start_date` / `finish_date` | string | ④ 信息卡「开始/读完日期」（为空则不显示） | 新增 |
| `cover` | /uploads/xx | ① Hero 封面（同步时下载到本地缓存） | 复用 |
| `notes_url` | string | ④ 信息卡「笔记链接」可点击（打开浏览器） | 新增 |
| `publisher` / `pub_year` / `isbn` / `douban_id` | string | ④ 信息卡「出版信息」行（出版社/年份/ISBN） | 新增 |
| `rating` | REAL 5 分制 | ④ 信息卡「评分 ★4.5」 | 新增 |
| `series_label` | string（如「基地 #3」） | ④ 信息卡「系列」 | 新增 |
| `files[]` | [{format,path,size}] | ④ 信息卡「可下载格式」列表 + ⑤ 下载按钮尺寸 | 新增 |
| `file_formats` | 逗号串 | ① Hero 格式角标（取首选格式） | 复用 |
| `identifiers` / `identifier_list` | [{type,label,value,url}] | ④ 信息卡「识别号」链接列表（ISBN/豆瓣/Goodreads 等可点击） | 新增 |
| `custom_display` | {label→字符串/数组} | ④ 信息卡「自定义字段」行（有才显示，动态渲染） | 新增 |
| `created_at` / `updated_at` | string | **不显示**（服务器时间戳，用户要求不出现来源/域名等信息） | — |
| `read_count` | int | **不显示**（服务器统计，同上） | — |
| `notes` / `read_logs` | JSON 数组 | **不显示**（笔记/阅读日志属用户私人数据，且本轮不做进度同步） | — |
| `custom` | 原始值 | 不显示（仅表单回填用） | — |

结论：**除 `notes`/`read_logs`/`custom` 三项（与"进度完全本地"目标冲突/仅表单用）及 `created_at`/`updated_at`/`read_count` 三项服务器信息（用户要求不显示）外，服务端详情字段在详情页 100% 可见**，没有放不下的字段——长文本用折叠、标签/识别号用流式 chips 和链接列表，自定义字段动态渲染。卡片只展示书籍本身的内容元数据，**不出现域名、来源标识、服务器地址、服务器时间戳等任何"服务器信息"**。

#### 3.4.2 展示内容

| 位置 | 内容 | 数据来源 |
|---|---|---|
| 复用现有 Hero | 封面、书名、作者、格式、阅读进度 | 本地 `Book` |
| 复用现有 MetricGrid | 进度、阅读时长、笔记、书签、活跃天数、文件大小 | 本地 Room（云端书时长为 0） |
| 复用现有 InfoCard | 文件名、格式、文件夹、本地标签、存储状态（「仅在云端」）、导入时间、最后阅读、路径 | 本地 `Book` |
| **新增图书信息卡** | 简介（4 行折叠/展开）、标签 chips、阅读状态（想读/在读/已读）、评分、系列、出版信息、识别号、自定义字段、可下载格式与大小（**不含域名/来源/服务器地址/时间戳等**） | `MyBooksMetaCache` + 按需详情刷新 |
| **新增下载操作** | 云端书：主按钮「下载 EPUB · 12.4 MB」+ 进度条；已下载：不显示 | `MyBooksSyncManager.downloadStates` |

#### 3.4.3 页面实际长什么样（示例值）

插入位置：[BookDetailsActivity.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookDetailsActivity.kt#L285-L310) 的 `LazyColumn` 里，`item { BookHero(...) }`、`item { MetricGrid(...) }`、`item { InfoCard(...) }` 之后，**第 4 个 item**。

```
┌──────────────────────────────────────────────┐
│ ‹  书籍详情                        [服务器书] │
│                                              │
│  ┌────┐  深度工作                              │  ← ① BookHero（原有）
│  │封面│  EPUB · 卡尔·纽波特                    │     数据：本地 Book
│  └────┘  效率 / 方法论                        │
│          0% · 仅在云端                        │
│                                              │
│  ┌────────┬────────┬────────┐                │  ← ② MetricGrid（原有）
│  │   0%   │   —    │ 12.4MB │                │     数据：本地 Room
│  │阅读进度 │阅读时长 │文件大小 │                │     （云端书时长/笔记/书签为 0）
│  └────────┴────────┴────────┘                │
│                                              │
│  ┌────────────────────────────────────────┐  │  ← ③ InfoCard（原有）
│  │ 存储        仅在云端                    │  │     数据：本地 Book
│  │ 格式        EPUB                       │  │
│  │ 本地标签     未分类                     │  │
│  │ 导入时间     2026-10-20                │  │
│  └────────────────────────────────────────┘  │
│                                              │
│  ┌────────────────────────────────────────┐  │  ← ④ 新增：图书信息卡
│  │ 图书信息                              │  │     数据：MyBooksMetaCache
│  │ 在无干扰状态下专注进行职业活动的能力，是  │  │     + GET /api/books/{id}
│  │ 稀缺且价值极高的技能。本书给出四条准则。  │  │     （不含域名/来源/服务器信息）
│  │ [效率] [方法论] [自我管理]              │  │
│  │ 状态 想读    评分 4.5    系列 —         │  │
│  │ ┌──────────────────────────────────┐   │  │
│  │ │ EPUB · 可下载                12.4 MB │   │  │
│  │ │ PDF  · 可下载                 8.1 MB │   │  │
│  │ └──────────────────────────────────┘   │  │
│  └────────────────────────────────────────┘  │
│                                              │
│  ┌────────────────────────────────────────┐  │  ← ⑤ 新增：下载主按钮
│  │        下载 EPUB · 12.4 MB              │  │     进度中显示进度环 + 百分比
│  └────────────────────────────────────────┘  │
│                                              │
│   删除   编辑   移动   笔记        阅读       │  ← 原有底部操作栏（已下载后可用）
└──────────────────────────────────────────────┘
```

数值示例来自真实字段：`title/author/summary/tags/status/rating/series_label/files[].format/size/updated_at`；`12.4 MB` 取自 `files[].size`，`文件大小` 取自本地 `remoteFileSize`。

三种状态：

| 状态 | 图书信息卡 | 底部按钮 |
|---|---|---|
| 未同步过（离线且无缓存） | 显示「同步后可查看简介」+ 重试 | 「下载」（点击时在线拉详情，失败提示） |
| 已同步、未下载 | 完整简介/标签/状态/评分/格式 | 「下载 EPUB · 12.4 MB」 |
| 已下载到本地 | 完整信息保留（标明来自服务器） | 不显示下载按钮；底部「阅读」可用 |

#### 3.4.4 新增文件

`app/src/main/java/com/helloac/mybooks/remote/`

- **`MyBooksMetaCache.kt`**：`ServerBookMeta` 数据类（summary/tags/status/rating/series/files/updatedAt），读写 JSON 缓存；
- **`MyBooksBookInfoCard.kt`**：Composable，入参 `book: Book`；内部用 `MyBooksConfig.remoteIdOf(book.id)` 读缓存，`LaunchedEffect` 触发详情刷新；渲染上述字段 + 下载按钮/进度（**只展示书籍内容元数据，不含域名/来源/服务器信息**）；无缓存时显示「同步后可查看完整信息」+ 重试。

#### 3.4.5 上游改动（2 处，各 1-3 行）

| 文件 | 改动 |
|---|---|
| `BookDetailsActivity.kt` | `LazyColumn` 在 `InfoCard` 之后追加：`if (MyBooksConfig.isMyBooks(book)) item { MyBooksBookInfoCard(book) }`；再加 1 行 import |
| `NavGraph.kt` | `requestOpenBook`（[L539-L547](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/navigation/NavGraph.kt#L539-L547)）的 `if (book.isCloudOnly)` 分支里最前面加：`if (MyBooksConfig.isMyBooks(book)) { BookDetailsActivity.start(context, book.id); return }`，其余 WebDAV 行为不变 |

#### 3.4.6 交互流程

```
书架长按/点按服务器书
  → 书籍详情页（图书信息卡：简介/标签/状态/评分/格式）
  → 点「下载 EPUB · 12.4 MB」
      → 进度条（复用 downloadStates）
      → 完成后卡片上的「仅在云端」变为本地，底部「阅读」可用
  → 点「阅读」/ 返回书架点封面 → 进入阅读器（进度只存本地）
```

- 已下载的服务器书再次进详情页，图书信息卡仍显示（离线也可见）；
- 长按菜单原有的「书籍详情」入口无需改动，自动受益；
- 删除仍走原有 `CloudAwareBookDeleteDialog`（`hasRemoteBooks` 为 false，只会本地删除）。

### 3.5 上游文件改动清单（共 7 个文件、9 处，均为追加式）

| # | 文件 | 具体改动 |
|---|---|---|
| 1 | `HomeViewModel.kt` | 构造函数注入 `MyBooksSyncManager`；`init` 加 `viewModelScope.launch { myBooksSyncManager.syncIfStale() }`；`loadWebdavSyncStatus` 中 `downloadStates` 收集改为 `combine(webdavSyncManager.downloadStates, myBooksSyncManager.downloadStates) { a, b -> a + b }`；`downloadCloudBook` 开头按 `MyBooksConfig.isMyBooks(book)` 分流到 `myBooksSyncManager.downloadBook`（成功后 `_downloadedBooks.emit(book)`）；新增 `fun syncMyBooksNow()`（失败写 `_uiState.error`，成功 emit 提示）；`deleteBooks` 过滤 `mybooks:` 书不参与 WebDAV 远端删除 |
| 2 | `HomeScreen.kt` | 头部云图标（已加）回调改为新参数 `onSyncCloudLibrary: () -> Unit`；文案「云端书库」→「同步服务器书库」 |
| 3 | `NavGraph.kt` | ① `HomeScreen(...)` 调用处传 `onSyncCloudLibrary = { homeViewModel.syncMyBooksNow() }`；② `requestOpenBook` 的云端分支前加 my-books 书 → 打开 `BookDetailsActivity` |
| 4 | `BookDetailsActivity.kt` | `InfoCard` 后追加 `MyBooksBookInfoCard(book)`（1 行 + 1 import） |
| 5 | `UpdateChecker.kt` | `CONFIG_URL`（[L26](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/util/UpdateChecker.kt#L26)）一行改为 `https://raw.githubusercontent.com/shyshineinn/Mybooks/main/docs/app-config.json`，其余逻辑全部不动 |
| 6 | `app/build.gradle.kts` | `applicationId = "top.helloac.mybooks"`；`versionCode = 1601`；`versionName = "16.1-mybooks"`；**namespace/包名保持 `com.huangder.lumibooks`** |
| 7 | `res/values*/strings.xml`（8 个语言目录） | `app_name = Mybooks` |

> 无 Room 迁移、无路由新增、无 Manifest 改动（回退上一轮的 Activity）。

### 3.5b 在线更新改成自己的（用户明确要求）

**机制**：Lumi 的更新检查是拉 GitHub 仓库里一份静态 `docs/app-config.json`（[UpdateChecker.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/util/UpdateChecker.kt#L11-L19)），改一行 URL 即指向自己的仓库。**注意 URL 是 `shyshineinn/Mybooks`（你的 fork），不是上游**。

**你需要在 fork 仓库里自建并维护 `docs/app-config.json`**（模板，字段与 UpdateChecker 的 `UpdateConfig` 严格对应）：

```json
{
  "latestVersion": "16.1",
  "latestVersionCode": 1601,
  "releaseUrl": "https://github.com/shyshineinn/Mybooks/releases/download/v16.1/Mybooks-v16.1.apk",
  "updateTitle": "发现新版本",
  "updateMessage": "已适配最新版 Lumi，修复若干问题。",
  "changelog": "",
  "forceUpdateBelowVersionCode": 0,
  "termsVersion": 0,
  "privacyVersion": 0,
  "notice": null,
  "fonts": []
}
```

**维护规则（决定更新是否弹出）**：
- 每次发版：`latestVersionCode` 必须 > 本地 `versionCode`（1601/1602…）才会提示；
- `releaseUrl` 指向你自己 GitHub Release 上传的 APK；
- `forceUpdateBelowVersionCode`：设 0 = 永远不强更；设 `1600` = 低于 1601 的版本强制更新；
- 若仓库还没有 `docs/app-config.json`：请求 404 → `UpdateChecker` 现有逻辑**静默跳过**（无弹窗、不崩溃），升级到 1601 前无影响；
- `notice` 可留 null；`fonts` 留空数组（字体不沿用上游的远程字体，避免指向 huangder 的下载源）。

**效果**：用户不再收到 Lumi 官方 APK 的更新提示；更新提示来自你的仓库、指向你的 APK。APK 由 `applicationId` 区分签名，你的包不会被官方更新覆盖，官方包也不会收到你的更新。

### 3.6 分支与跟版（不变）

```
main     ← 仅 ff-only 跟随 upstream/main，不直接提交
custom   ← 默认分支，全部魔改
```

- `.github/workflows/sync-upstream.yml`：每日把 `upstream/main` 快进到 `main`，再对 `custom` 开合并 PR；
- 本地 `git config rerere.enabled true`；
- `versionCode` 规则 `上游主版本×100 + 自建序号`；
- 自备 keystore（CI secrets，不入库）；GPLv3 需继续开源。

## 四、假设与决策

| 项 | 决策 | 理由 |
|---|---|---|
| 服务器地址 | 常量 `https://helloac.top` | 个人单服务器，无需设置页；换地址改一行 |
| 融合粒度 | 只读同步（服务器→本地） | 用户要求"全部存在本地"，不上传进度 |
| 服务端删除同步 | 不自动删本地 | 避免误删已读书籍 |
| 书库规模 | 全量拉取 | 个人库；上千本再考虑分页 |
| 封面 | 同步时缓存到本地 | 离线可显示；失败不阻塞入库 |
| 手动刷新入口 | 首页头部云图标 | 复用已加按钮，不加新页面 |
| 详情页 | 复用 `BookDetailsActivity` + 追加图书信息卡 | 不新建页面，1 行接入 |
| 服务器书的点按行为 | 打开详情页（非直接弹下载框） | 先看简介/标签再决定下载；WebDAV 书保持原行为 |
| 详情元数据存储 | JSON 缓存到 `filesDir/mybooks/meta/` | 不改 Room schema，离线可见，下载后仍保留 |
| 应用名称 | `Mybooks` | 用户明确要求 |
| 在线更新 | `UpdateChecker.CONFIG_URL` → `shyshineinn/Mybooks` 的 `docs/app-config.json` | 用户明确要求；更新源改为自己的 fork + 自己的 GitHub Release |
| 图书信息卡内容 | 只展示书籍内容元数据（简介/标签/状态/评分/系列/出版/识别号/自定义字段/格式）；**不含域名、来源标识、服务器地址、服务器时间戳、服务器统计** | 用户明确要求 |

## 五、验证步骤

1. **接口预检**（无需 App）：
   ```powershell
   (Invoke-RestMethod https://helloac.top/api/books).Count
   Invoke-RestMethod https://helloac.top/api/books/1 | Select-Object id,title,files
   ```
   再用 `files[0].path` 拼 `https://helloac.top/books-files/<path>` 验证可下载。
2. **编译**（本机无 JDK/SDK，需 Android Studio）：
   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```
3. **手工验收**：
   - 首次启动 → 书架出现服务器书（云端角标 + 封面），与本地书混排；
   - 点服务器书 → 进「书籍详情」：Hero/Metric/Info 正常，图书信息卡显示简介、标签、状态、评分、可下载格式；
   - 详情页点「下载 EPUB」→ 进度条 → 完成后「仅在云端」变本地，「阅读」可用；
   - 已下载后再次进详情，图书信息卡仍在（断网也显示缓存内容）；
   - 下载一本书 → 回到书架，卡片角标消失、可直接阅读；
   - 阅读几页 → 杀进程重开 → 进度仍在；服务器不会收到任何进度（抓包只有 GET）；
   - 删除某本已下载书（仅本地）→ 下次同步重新出现为云端占位；
   - 关闭网络启动 → 书架正常、同步静默失败、已有书可读；
   - 重复点同步不会产生重复书（`sourceUri` 去重）；
   - 若同时配置了 WebDAV，两侧书互不干扰（my-books 书不进 WebDAV 上传/删除）。
4. **品牌/更新验收**：
   - 桌面图标与应用名显示 `Mybooks`（8 个语言目录均已改）；
   - 安装包 `applicationId = top.helloac.mybooks`，与官方 Lumi 可共存；
   - 手动检查更新：`latestVersionCode` 设为小于本地的值 → 提示「已是最新」；设为大于本地的值 → 弹出更新框，确认 `releaseUrl` 指向 `shyshineinn/Mybooks` 的 Release，而非 huangder；
   - 删除/不提供 `docs/app-config.json` → 更新检查静默失败，不弹官方更新。
