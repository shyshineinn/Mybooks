# mybooks 服务端连接

当前客户端只接入 fork 的原生服务端，不再连接原自创的 Express 后端。WebDAV、本地阅读和备份仍独立提供。

## 命名与位置

- App 显示名称：`mybooks`。
- 应用 ID：`top.helloac.mybooks`，不附加 preview/server 后缀。
- 内部源码包：`com.huangder.lumibooks`，保留上游路径以减少合并冲突。
- 本地目录：`C:/littleBIngbing/mybooks-android`。
- GitHub：`https://github.com/shyshineinn/mybooks-android`。
- 服务端：`C:/littleBIngbing/mybooks-server`，GitHub 为 `shyshineinn/mybooks-server`。
- 当前本地分支：`feat/server-connection`；`upstream` 仍指向原 Lumi_Books。

## 当前接入范围

使用原生表单登录、Cookie 会话、书库增量、详情、封面和正文下载。密码不持久化，会话沿用 Android Keystore 加密存储；凭据只发往匹配的源和配置路径。

同步通过 `/api/book/catalog_changes` 获取书目变更。首次同步或游标失效时返回完整的精简书目索引，后续只返回变化的书目，再按需取详情。游标在本轮完整双向同步成功后保存。服务端重启或游标被淘汰时自动重建索引，缺少书目仍不代表删除。未提供增量接口的服务端可继续通过原生分页同步。

原生字段在 `PoxenApiClient` 转换为现有本地模型。服务端原生书目输出增加 Calibre UUID 和精确修改时间，下载前检查书籍身份。

书目编辑及未读、在读、已读状态使用现有本地队列。开启自动同步时联网后提交，也可手动同步。App 调用服务端原生 `/api/book/{id}/edit` 和 `/api/book/{id}/readstate`，提交时校验 UUID、书目修改时间或当前账号的阅读状态。两端改了同一字段时保留本地待同步修改，由用户选择冲突处理方式。编辑书目需要账号有服务端编辑权限。

删除沿用现有删除菜单和待同步队列，不新增界面。云端删除调用 `/api/book/{id}/delete` 并提交 UUID 和书目修改时间；失败时普通删除保留本机内容，强制删除保留待处理删除并重试。App 通过 `/api/book/deletions` 获取显式删除记录，只在 UUID 匹配且没有待上传修改时清理本机副本；从回收站恢复后以在架书目为准。旧服务端没有删除记录接口时，App 保留本机副本；云端删除会提示升级服务端。

本地导入且未关联服务器的正文会随自动同步或“立即同步”上传到 `/api/book/upload`；超过 8 MiB 使用分块接口。App 上传前计算 SHA-256，并核对服务端返回的 `sha256`；`samebook` 只在服务器已有文件校验值一致时关联，避免把同名不同内容误当成已上传。关联成功后沿用现有导入/同步界面，把导入确认页的书目字段和豆瓣/自定义封面写回服务器；失败进入现有待上传问题列表并重试。Markdown 正文暂不上传。

阅读状态弹窗提供未读、想读、在读和已读四个单选项。App 通过 `/api/book/{id}/readstate` 一次提交对应的 `read_state` 和 `wants`。收藏及书籍的虚拟目录双向同步。服务端分类作为只读标签参与浏览，普通标签仍独立双向同步。目录最多两级，实际文件和系统授权目录由本机管理。

阅读时间线随书籍详情下发。App 通过同一阅读状态接口同步开始、读完、重读轮次及手动改期。服务器按账号和书籍 UUID 保存时间线，单独改期保留当前阅读状态。

连接设置可分别选择同步个人资料与设置、书架布局。资料同步复用 App 的便携设置规则，头像、字体和阅读背景按文件校验值传输。书架布局同步置顶、虚拟目录及 App 专用目录归属。普通标签、收藏和服务器两级目录仍由书目接口维护，设备授权和实际存储目录保留在本机。

豆瓣填充通过服务端 `/api/book/0/refer` 查询已启用的元数据源，候选字段在客户端转换为现有编辑页结构。服务端未启用可用信息源或豆瓣代理不可达时，界面显示未找到或查询失败。

已关联书籍的封面使用书籍 UUID 和封面内容版本校验后上传。已下载正文如果在服务端被替换，App 会下载并校验新版再切换本地副本。正在阅读的书会延后切换。旧正文的阅读位置和标注保留原正文身份。

服务端删除书籍成功后才清理书目关联。进入回收站的书籍保留阅读状态、时间线和阅读数据，恢复后继续使用原记录。管理员彻底清理回收站时删除相应的阅读数据。

Web 阅读器的进度与批注使用独立数据。Android 保留阅读器笔记与划线，书目级心得和自定义字段已从 App 移除。

本地数据库、阅读器和现有书籍未被清空；旧服务器书库关联尚未自动迁移至新服务端。沿用旧应用 ID，是否能覆盖安装还取决于签名一致性。当前未自动安装 APK。

## 构建与验证

```powershell
$env:MYBOOKS_TEST_CREDENTIALS='C:/littleBIngbing/mybooks-server/.local/app-test-credentials.json'
.\gradlew.bat :app:testReleaseUnitTest --tests '*PoxenApiClientTest' --tests '*PoxenLiveConnectionTest' --tests '*MyBooksAuthClientTest' --tests '*MyBooksSyncSafetyTest' --tests '*MyBooksResumeDownloadTest' --tests '*MyBooksReadingMergeTest' --tests '*MyBooksFileUrlTest' --tests '*MyBooksIncrementalProtocolTest' :app:assembleRelease -PabiFilter=arm64-v8a --console=plain --max-workers=2
```

`PoxenLiveConnectionTest` 只允许显式配置的 loopback 测试服务，凭据文件不进入源码、APK 或测试报告。

没有权威校验头的下载不会拼接不可靠的断点；完成后计算本地 SHA-256。电脑上的真实网络测试不代表已完成手机安装或阅读器交互验收。
