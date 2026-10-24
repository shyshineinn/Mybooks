# MyBooks 安卓端魔改：服务器书库融合进本地书架

> 状态：**已实现**（本文件按当前代码现状维护，不是待执行方案）
> 最后核对：2026-10-24 / 代码版本 2.2.10 (versionCode 2210)
> 服务端仓库：`C:\littleBIngbing\my-books`

---

## 一、目标与边界

把 `https://helloac.top`（my-books）上的书**直接变成 Lumi 书架里的书**，与本地导入的书混排：

- 书架里能看到服务器上的书（封面、书名、作者），带云端角标；
- 下载完成后原地变成可阅读的本地书；
- 阅读进度、笔记、书签、统计**只存在本地**，不回传；
- 不新增独立书库页面，复用现有书架、封面角标、详情页、删除弹窗、阅读器。

**明确不做**：云端阅读进度同步、笔记回传、服务器端统计展示。

---

## 二、代码落点（现状）

魔改代码当前放在**上游包内**，不是独立 `com.helloac.mybooks` 包：

| 层 | 路径 | 文件 |
|---|---|---|
| 数据层 | `app/src/main/java/com/huangder/lumibooks/data/sync/` | `MyBooksConfig.kt` (38)、`MyBooksApiClient.kt` (347)、`MyBooksSyncManager.kt` (850)、`MyBooksMetaCache.kt` (238)、`MyBooksMerge.kt` (383)、`MyBooksNotesStore.kt` (68)、`MyBooksCoverUploader.kt` (70)、`RemoteBook.kt` (201)、`JsonText.kt` (25) |
| UI 层 | `app/src/main/java/com/huangder/lumibooks/ui/bookshelf/` | `MyBooksBookInfoCard.kt` (321)、`ServerMetaDisplay.kt` (179)、`MyBooksEditSheet.kt` (1210)、`MyBooksEditViewModel.kt` (1293)、`MyBooksNotesActivity.kt` (365)、`MyBooksNotesViewModel.kt` (269) |
| 公共组件 | `app/src/main/java/com/huangder/lumibooks/ui/components/` | `PullToRefreshHint.kt` (79) |
| 持久层 | `app/src/main/java/com/huangder/lumibooks/data/local/` | `dao/MyBooksSyncStateDao.kt` (41)、`entity/MyBooksSyncStateEntity.kt` (33) |
| 测试 | `app/src/test/java/com/huangder/lumibooks/data/sync/` | `MyBooksJsonTextTest.kt`、`MyBooksMergeTest.kt` |

新增合计约 6000 行。`BookDownloadState` / `CloudBookDownloadResult` 复用上游 `data/sync/WebdavSyncManager.kt` 中的定义，不另造一套。

> **与早期方案的差异**：早期方案要求新建 `com/helloac/mybooks/remote/` 包以"零上游代码改动"。实际实现放进了上游包内，理由是 `BookDownloadState` 等类型已在 `data/sync` 中、跨包引用反而更绕。代价是上游合并时这 14 个新文件是"新增到上游目录"，冲突面比独立包大。详见 §9 待办。

---

## 三、数据模型与映射

服务器书 → 本地 `Book`（[MyBooksSyncManager.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksSyncManager.kt#L328-L345)）：

| Book 字段 | 取值 |
|---|---|
| `id` | `"mybooks-" + remoteId` |
| `title` / `author` | 服务端详情字段 |
| `filePath` | `""`（占位），下载后写入 |
| `coverPath` | 同步时缓存到 `getCoversDirectory()/mybooks_cover_{remoteId}_{hash}.jpg` |
| `format` | 首选文件格式，无可用文件时 `EPUB` |
| `isCloudOnly` | `true`，下载后置 `false` |
| `remoteLibraryKey` | `"mybooks:helloac.top"` |
| `remoteFileName` | **null**（见 §3.1） |
| `remoteFileSize` | 首选文件 size |
| `sourceUri` | `"mybooks://book/" + remoteId`（去重键） |
| `metadataUpdatedAt` | 服务端 `updated_at` |
| `readingProgress` | `0f`，已存在记录保持原值 |

### 3.1 `remoteFileName = null` 的原因

上游 `deleteBooks` 只对 `remoteFileName != null` 的书调 WebDAV 删除，WebDAV 上传循环也只处理有 manifest 条目的云书。设为 null 后 my-books 的书不会被 WebDAV 逻辑碰到，也不会因 `LOCAL_ONLY` 删除留下幽灵占位。

代价：`syncedBookIds`（WebDAV"已同步"集合）不含它们，但云端角标来自 `isCloudOnly`，视觉不受影响。

---

## 四、同步流程

### 4.1 拉取

入口：[HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L222) 启动时 `syncIfStale()`（10 分钟节流）、[BookshelfScreen.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookshelfScreen.kt#L184) 书架列表/网格下拉（CoverFlow 不启用）、[HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L2346) `syncMyBooksNow()`。

`runSyncNow()`（[MyBooksSyncManager.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksSyncManager.kt#L261-L313)）：

1. `Mutex` 防并发；先推送待删队列；
2. `GET /api/books` 拿全量列表；**列表为空且本地有书时跳过删除**（防服务端异常导致误删）；
3. 逐本 `syncOneBook()`（[L315](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksSyncManager.kt#L315)）：
   - 不存在 → 拉详情、选首选文件、下封面、`insertBook(isCloudOnly = true)`、写基线快照、同步标签与心得；
   - 已存在 → 走 §4.2 合并；
   - 单本失败只记 `lastError`，不影响其他书；
4. `pruneRemovedBooks()`（[L667](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksSyncManager.kt#L667)）：服务器已无此书 → 删本地正文/封面/索引/TXT 缓存、删 books 记录、删同步状态、删心得与元数据缓存。

### 4.2 推送：三方合并

[MyBooksMerge.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksMerge.kt#L49) 是纯函数：

```
base   = 上次同步的服务器快照（mybooks_sync_state.baseJson）
local  = 本地当前值（Book 字段 + overrides + 标签表）
remote = 本次服务器详情
```

受管标量字段：`title / author / status / summary / rating / series / series_index / publisher / pub_year / isbn / douban_id / notes_url`。

判定规则：

- 本地没改 → 采用服务器值，并清掉对应 override；
- 只有本地改 → 打进 patch 推给服务器；
- 双方都改 → 比较 `editedAt`（本地修改时间）与服务器 `updated_at`，取较晚者；
- `notes_url` 恒取基线值（App 不编辑心得链接）。

标签独立合并（`baseTagsJson` vs 本地标签表），有差异时 `patch.put("tags", ...)`。封面单独走 `coverDirty / coverEditedAt` 判定方向。

推送走 `PATCH /api/books/:id`，带 `expected_updated_at`；**409 冲突**时用服务端当前值重跑一次合并再试（[MyBooksSyncManager.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksSyncManager.kt#L414-L430)）。

### 4.3 删除

本地删书（[HomeViewModel.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L2288)）：

- `LOCAL_ONLY` → 只删本地，服务器书保留；
- `LOCAL_AND_CLOUD` → 删本地 + 把服务器软删除（进回收站）排进 `mybooks_pending_delete` 队列，由同步器推送 `DELETE /api/books/:id`；
- `FORCE_LOCAL_AND_CLOUD` 按钮对 my-books 书**不显示**（[CloudBookUi.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/components/CloudBookUi.kt#L71)）。

删除弹窗对服务器书分三种文案：仅本地 / 本地+服务器 / 混合删除提示；无写令牌时只给"仅本地"并提示只读。

### 4.4 下载

`MyBooksSyncManager.downloadBook()`（[L744](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksSyncManager.kt#L744)）：

1. 反解 remoteId → 拉详情取最新 `files` → 选首选格式；
2. 从 `/books-files/{path}` 流式下载到 `.part`，每 64KB 更新 `downloadStates`（复用上游 `BookDownloadState`）；
3. 成功 `renameTo` 正式文件 → 写 `markBookDownloaded`（`isCloudOnly` 置 0）；
4. 失败清 `.part`，状态 `Failed`。

下载入口有两条：

- **详情页右上角「更多」菜单 →「下载」**（[BookDetailsActivity.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookDetailsActivity.kt#L388-L450)）：`isCloudOnly` 且服务器存在可读格式时显示该项；下载中副标题显示 `book_download_progress`（百分比），完成后该书 `isCloudOnly` 置 0、入口消失。调用 [HomeViewModel.downloadCloudBook](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/HomeViewModel.kt#L519-L524) 按 `isMyBooks` 分流；文件卡的「存储位置」行同步显示下载进度，完成/失败用 Toast 提示。
- **详情页底部「阅读」按钮** → 未下载时走上游 `CloudBookDownloadDialog` 确认 → 确认后下载并自动进阅读器（[NavGraph.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/navigation/NavGraph.kt#L580-L617)）。

书架长按封面弹窗不再提供下载项（保持上游形态，避免两处入口语义重叠）。

### 4.5 读书心得

服务器侧正文存 `notes-files/{id}.md`，接口是 `GET/PUT /api/books/:id/notes`（[routes.notes.js](file:///C:/littleBIngbing/my-books/src/http/routes.notes.js)）。

App 侧：

- 本地阅读副本存 `filesDir/mybooks/notes/{remoteId}.md` + 同名 `.json` 元数据（[MyBooksNotesStore.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksNotesStore.kt#L17-L68)）；
- **阅读页只渲染本地副本**，不直接渲染网络内容（[MyBooksNotesActivity.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/MyBooksNotesActivity.kt#L280-L328)，WebView 禁 JS、禁文件访问）；
- 详情页右上「更多」菜单：上传/更新 `.md`（限 UTF-8、≤5MB）、「同步并阅读」；
- `PUT` 带 `expected_updated_at`，409 → 提示"服务器心得已更新，请先同步再上传"。

---

## 五、服务端接口（App 实际调用）

| 方法 | 路径 | 鉴权 | 用途 |
|---|---|---|---|
| GET | `/api/books` | 免 | 全量列表（不含 files） |
| GET | `/api/books/:id` | 免 | 单本详情（含 `files` / `identifier_list` / `series_label` / `custom_display`） |
| PATCH | `/api/books/:id` | Bearer | 局部更新，带 `expected_updated_at` |
| DELETE | `/api/books/:id` | Bearer | 软删除（进回收站，保留 14 天） |
| POST | `/api/books` | Bearer | 新建书 |
| POST | `/api/books/:id/file` | Bearer | 上传正文文件 |
| GET | `/api/books/:id/notes` | 免 | 读心得元信息与正文 |
| PUT | `/api/books/:id/notes` | Bearer | 写心得 |
| GET | `/api/custom-fields` | 免 | 自定义字段定义（编辑页动态渲染） |
| GET | `/api/isbn/lookup?isbn=` | 免 | 豆瓣填充（ISBN 优先） |
| GET | `/api/metadata/search?q=` | 免 | 豆瓣填充（书名候选） |
| POST | `/api/upload` | Bearer | 上传封面（data URL） |
| GET | `/uploads/xxx` | 免 | 封面静态托管 |
| GET | `/books-files/{path}` | 免 | 正文静态托管 |

鉴权规则：GET/HEAD/OPTIONS 免令牌，其余需 `Authorization: Bearer <admin-token>`。Nginx 层已关闭 Basic 认证，否则会拦截 App 的 Bearer 请求。

---

## 六、详情页呈现（现状）

复用现有 [BookDetailsActivity.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookDetailsActivity.kt)，**没有新建"图书信息卡"**，服务器字段分两处插入既有卡片：

### 6.1 内容卡（ContentInfoCard）

原有行：书名、作者、文件夹、标签（本地标签表，服务器标签已由 `applyTags` 合并进本地）。

服务器追加行（[MyBooksContentRows](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/MyBooksBookInfoCard.kt#L111-L145)）：

| 展示 | 数据源 |
|---|---|
| 简介（4 行折叠，可展开/收起） | `summary` |
| 阅读状态 | `status` → 想读/在读/已读 |
| 评分 | `rating` → `x.x / 10`（**服务端 v8 起为 10 分制**） |
| 系列 | `series_label` 或 `series #series_index` |
| 出版 | `publisher · pub_year` |
| ISBN | `isbn` 或 identifiers 中的 isbn |
| 其他识别号 | `identifier_list`，有 `url` 时可点开浏览器 |
| 自定义字段 | `custom_display`，动态渲染，跳过保留字 |
| 阅读记录 | `read_logs` → chips；有日志时额外显示「共 N 次」 |
| 阅读日期 | `start_date` / `finish_date`（无日志时） |
| 阅读次数 | `read_count`（无日志无日期时） |
| 读书心得 | 本地副本与服务器元信息对比（不同步/已同步/仅本地/服务器缺失） |
| 同步状态 | 待同步项数 / 上次失败 |

**不展示**：服务器地址、来源标识、`created_at` / `updated_at` 时间戳、域名。

### 6.2 文件卡（FileInfoCard）

服务器书追加「电子书文件」行（`files[]` 去重按格式优先序，格式 + 体积，如 `EPUB 12.4 MB`）；无文件时显示"暂无电子书文件"或"暂无可阅读格式"。云端书不显示文件名/导入时间/最后阅读/路径。

### 6.3 编辑

详情页底部「编辑」对服务器书打开 [MyBooksEditSheet.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/MyBooksEditSheet.kt#L94)（底部 Sheet，62% 高），分区：基本信息 / 标识符 / 自定义字段 / 阅读记录。

- 豆瓣填充：ISBN 优先（`/api/isbn/lookup`），未命中再按书名检索（`/api/metadata/search`）；豆瓣被拒（blocked）时不再自动转书名检索，只提示用户手动重试。只回填文字字段，不自动改标签/封面、不自动保存。
- 保存（编辑既有书）：写 override → `replaceLocalEdits` → `schedulePush()` 推送；书名/作者只写被改动的字段（整行回写会覆盖同期同步进来的新值）；豆瓣带回的标签写成本地标签，封面落到本地封面目录并 `recordCoverChange` 标记待上传。
- 保存（新建书，走导入 Sheet）：先 `POST /api/books` 建书，再 `POST /api/books/:id/file` 传正文；正文上传失败会回滚删除刚建的书，不在服务器留空壳。

### 6.4 导入到服务器书库

导入 Sheet（[ImportBooksSheet.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/home/ImportBooksSheet.kt#L351-L368)）提供三个动作：从文件导入 / 从授权文件夹导入 / 授权一个书籍文件夹。

选完文件后（[NavGraph.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/navigation/NavGraph.kt#L384-L395)）：

- **有写令牌** → 逐本打开 `MyBooksEditSheet`（`createSource` 模式）补信息 → 建书 + 传正文 → 全部完成后 `syncMyBooksNow()` 刷新书架；
- **无写令牌（只读构建）** → 退回本地导入，不上传服务器。

建书成功后，本地正文副本（`importLocalCopyOfServerBook`）必须沿用**服务端归一化后的书名/作者**（`MyBooksCreatedBook`，由建书接口响应带回），不能退回文件名/未知作者：本地行是同步的三方合并输入，用文件来源的值会与服务器基线冲突，被当成「本地编辑」推回服务器，把豆瓣填好的书名作者覆盖掉。

书架菜单的「刷新授权文件夹」已移除，扫描只在授权文件夹页执行。

---

## 七、数据库、令牌与发布

### 7.1 Room

版本 **20**（[AppDatabase.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/local/database/AppDatabase.kt#L50)），迁移 [MIGRATION_19_20](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/local/database/DatabaseMigrations.kt#L273-L300) 新增两表：

- `mybooks_sync_state`：三方合并基线（`baseJson` / `overridesJson` / `editedAtJson` / `baseTagsJson` / `coverDirty` / `coverEditedAt` / `lastError`）；
- `mybooks_pending_delete`：待推送的服务器软删除队列。

DAO 由 [AppModule.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/di/AppModule.kt#L133) 提供。

### 7.2 令牌

`local.properties` 的 `MYBOOKS_ADMIN_TOKEN` 编译进 `BuildConfig.MYBOOKS_ADMIN_TOKEN`（读取见 [app/build.gradle.kts](file:///C:/littleBIngbing/mybooks/app/build.gradle.kts#L31-L36)，注入见 [L56](file:///C:/littleBIngbing/mybooks/app/build.gradle.kts#L56)），不做前端输入/显示。**为空时 App 自动只读**：只拉不推，编辑/上传/删除入口隐藏或提示只读。

### 7.3 品牌与更新

- `applicationId = top.helloac.mybooks`，`namespace` 保持 `com.huangder.lumibooks`；
- 8 个语言目录 `app_name` 均为 `Mybooks`；
- 在线更新走 [UpdateChecker.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/util/UpdateChecker.kt#L24-L25) → `https://raw.githubusercontent.com/shyshineinn/Mybooks/main/docs/app-config.json`；
- 仓库内 `docs/app-config.json` 与服务器 `public/app-config.json` **两份并存**，需要手动保持 `latest_version_code` 同步，否则不会弹更新提示。

---

## 八、验证清单

```powershell
# 接口预检
(Invoke-RestMethod https://helloac.top/api/books).Count
Invoke-RestMethod https://helloac.top/api/books/1 | Select-Object id,title,files
# 本地构建
.\gradlew.bat :app:assembleRelease -PabiFilter=arm64-v8a
```

手工验收：

- 首次启动 → 书架出现服务器书（云端角标 + 封面），与本地书混排；
- 点服务器书 → 进详情页：简介/状态/评分/系列/出版/ISBN/识别号/自定义字段/阅读记录均可见，**无域名/时间戳**；
- 详情页右上角「更多」→ 「下载」→ 菜单项副标题显示百分比进度、文件卡「存储位置」行同步显示进度 → 完成后入口消失、云端角标消失、可直接阅读；
- 详情页底部「阅读」（未下载时）→ 弹下载确认 → 确认后下载并自动进阅读器；
- 断网进详情页 → 简介等缓存内容仍可见，"重试"可用；
- 编辑 → 豆瓣填充 → 保存 → 刷新后服务端与本地一致；
- 详情页「更多」→ 上传 `.md` → 「同步并阅读」→ 心得页渲染正常；
- 本地删服务器书（本地+服务器）→ 下次同步服务端也消失；
- 服务器删书 → 下次同步本地该书消失（含已下载的正文与封面）；
- 关闭网络启动 → 书架正常、同步静默失败、已有书可读；
- 重复同步不产生重复书（`sourceUri` 去重）；
- 配置 WebDAV 时两侧书互不干扰。

---

## 九、已知偏差与待办

以下为 2026-10-24 对照 `devdocs/` 规范（`style.md`、`ui-design-spec.md`、`design-spec.md`、`technical-spec.md`、`lumi-ui-motion-design-system.md`）核出的问题，按优先级排列。

### P0

1. **详情页「下载」入口仍偏弱**
   已把下载入口从书架长按弹窗迁到详情页右上角「更多」菜单（见 §4.4），但方案想要的是页面主按钮「下载 EPUB · 12.4 MB」+ 独立进度条；现状是菜单项 + 文件卡「存储位置」行显示进度，没有独立主按钮与文件大小文案。

2. **包结构与既定约束冲突**
   方案与项目约定均要求魔改代码放独立 `com.helloac.mybooks` 包、"零上游代码改动"。现状 14 个新文件在上游包内（§2），与项目硬约束冲突，上游合并冲突面也更大。
   另：`git status` 显示旧路径 `com/helloac/mybooks/remote/*.kt` 处于 `D`（已删未提交）、新文件为 `??`（未跟踪），迁移处于半完成状态。

3. **国际化缺口**
   - `strings.xml` 新增 121 条 `mybooks_*` **只加在 `values/`**，其余 7 个语言目录（en/ja/ko/zh-rCN/zh-rHK/zh-rMO/zh-rTW）全为 0 条；
   - [ServerMetaDisplay.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/ServerMetaDisplay.kt) 有 40 处硬编码中文（想读/在读/已读、阅读状态、评分、系列、出版、简介、阅读记录、共 N 次、暂无电子书文件…），[MyBooksBookInfoCard.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/MyBooksBookInfoCard.kt#L194-L304) 另有 8 处。
   上游 UI 文件几乎零硬编码中文（全走 `stringResource`），这两处是例外；英文/日文用户会看到混杂的中文界面。

### P1

4. **上游侵入面超出方案预估**
   方案预估"7 个文件、9 处，均为追加式"、冲突点 6 处。现状未提交工作区改了 35 个上游文件、+1247/−229 行；[BookDetailsActivity.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/BookDetailsActivity.kt) +301/−36 且**重构了上游 `InfoCard`**（拆成 ContentInfoCard / FileInfoCard）。这与"最小侵入式改动以降低后续合并冲突"的既定偏好相反。
   （注：其中 `SplashScreen` / `AppIconStyle` / `LauncherComponentSelection` / `AppIconStyleScreen` / `LaunchThemeController` / `EBookReaderApp` / `third_party` 属 BOOK_LINE 图标特性，与本方案无关。）

5. **方案文档表格已过时**（代码是对的，文档错）
   - `rating`：服务端 v8 起为 10 分制，代码显示 `x.x / 10` 正确，方案写"5 分制 ★4.5"错；
   - `read_count` / `read_logs`：现按用户后续要求展示，方案表格仍写"不显示"；
   - `notes_url`：已解析进 [MyBooksMetaCache.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksMetaCache.kt#L43) 但详情页无对应行，被 v3 的心得流程取代；
   - 手动同步入口：v3 表格同时保留"首页头部云图标"和"设置页入口已移除"两行，现状是设置页入口已移除、改为书架下拉刷新。

6. **标签 / 状态未用 chips**
   方案要求"标签 chips（水平流式换行）"、"状态 chips"。现状服务器标签被合并进本地标签表（[applyTags](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/data/sync/MyBooksSyncManager.kt#L632)）后以普通文本行展示，状态也是文本行；`AnnotationTagChips` 反倒用在了"阅读记录"上。

7. **展示逻辑无单测**
   [ServerMetaDisplay.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/ServerMetaDisplay.kt) 的 `statusDisplay` / `ratingDisplay` / `seriesDisplay` / `publicationDisplay` / `hasMeaningfulSummary` / `customRows` / `identifierRows` 全是纯函数却零覆盖；上游同类（`shouldStackDetailRow` → `BookDetailsLayoutTest`）都配了测试。

### P2

8. [MyBooksEditSheet.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/MyBooksEditSheet.kt#L37-L39) 残留未使用的 `ModalBottomSheet` / `rememberModalBottomSheetState` import（已改用 `LiquidGlassBottomSheetScaffold`）。
9. [MyBooksBookInfoCard.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/MyBooksBookInfoCard.kt#L42) 同包自引用 `import ...ui.bookshelf.DetailRow`。
10. [MyBooksEditSheet.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/ui/bookshelf/MyBooksEditSheet.kt#L1097) `AppRoundedCornerShape(18.dp)` 应为 `AppRadius.lg`。
11. **更新源分裂且与项目约定不符**
    项目约定"在线更新地址固定为 `https://helloac.top/app-config.json`"，但 [UpdateChecker.kt](file:///C:/littleBIngbing/mybooks/app/src/main/java/com/huangder/lumibooks/util/UpdateChecker.kt#L24-L25) 实际读的是 GitHub raw 的 `docs/app-config.json`。
    且两份配置都落后于当前版本：仓库 `docs/app-config.json` 是 2210、服务器 `public/app-config.json` 是 1601，都 < 2210，**永远不会弹更新提示**。发版时需手动同步这两份文件。
12. 方案示意图里的顶部 `[服务器书]` 标签未实现（现靠封面云端角标表达来源）。

---

## 十、跟版约定

- 仓库只有一条 `main` 分支，直接对应 `origin`（`shyshineinn/Mybooks`），魔改全部在此分支；`upstream`（`huangder/Lumi_Books`）作为上游参照。
- 上游合并冲突面见 §9 第 4 条：优先靠"新文件 + 少改上游行"控制，`BookDetailsActivity` 的 `InfoCard` 拆分是最大冲突点。
- `versionCode` 规则：`主版本 × 1000 + 次版本 × 100 + 补丁号`（2.2.10 → 2210），与 `versionName` 一一对应且单调递增。
- CI（`.github/workflows/build-release.yml`）手动下载 cmdline-tools、装 `platforms;android-37.0` + `build-tools;36.0.0`（与本地 `buildToolsVersion` 一致），签名用同一套 keystore secrets。
- 正式发布使用统一签名密钥（`local.properties` 的 `MYBOOKS_KEYSTORE_*` 四项），本地与 CI 同一套配置，避免签名不一致导致无法覆盖安装。
- GPLv3，需继续开源。
