# My books 安卓改版：部署、构建与发布

本仓库：[shyshineinn/Mybooks](https://github.com/shyshineinn/Mybooks)。
上游：[Huangder/Lumi_Books](https://github.com/huangder/Lumi_Books)。
后端：[shyshineinn/my-books](https://github.com/shyshineinn/my-books)。

## 连接服务器

先按后端的 docs/SELF_HOSTING.md 部署并在终端初始化管理员。
在「设置 → 服务 → My books 服务器」配置你自己的地址、用户名和密码。
新安装不预填维护者的线上地址；升级保留已保存的服务器配置。
应用不内置管理令牌，密码不落盘，设备会话使用 Android Keystore 加密保存。
个人自部署采用单管理员、多设备；不提供多人账户隔离。

书目同步支持版本检查、明确删除记录和冲突选择，见 [同步协议](mybooks-sync-protocol.md)。
自建后端尚未同步阅读位置、书签和标注；WebDAV 同步仍为独立功能。
改版以前的上游文档可能描述不同版本，以本文件及当前代码为准。

## 构建环境

- Gradle Wrapper 8.14.3，JVM 字节码目标为 Java 17。
- Gradle 守护进程由 gradle/gradle-daemon-jvm.properties 指定 JetBrains JDK 21；首次可能下载。
- compileSdk / targetSdk 37，SDK 平台包 android-37.0，Build Tools 36.0.0；最低 Android 8 / API 26。
- 首次构建需要联网获取 Gradle、JDK、SDK 和 Maven 依赖；不要在首次构建时使用 --offline。
- 在 Android Studio 配置 SDK，或复制 local.properties.example 为 local.properties 并填自己的路径。

```sh
git clone https://github.com/shyshineinn/Mybooks.git
cd Mybooks
./gradlew -PabiFilter=arm64-v8a assembleDebug
./gradlew -PabiFilter=arm64-v8a assembleRelease
```

Windows 使用 .\gradlew.bat。签名配置不是构建源码的必要条件；
无私有签名配置时 Release 产出 unsigned APK，Debug 使用开发签名。
需要正式签名时在被忽略的 local.properties 中设置 MYBOOKS_KEYSTORE_FILE、
MYBOOKS_KEYSTORE_PASSWORD、MYBOOKS_KEY_ALIAS、MYBOOKS_KEY_PASSWORD。
不要提交这些值或复用原作者/维护者的私钥。
本地 libs、third_party、字体与许可文件属于源码构建输入，不应为了缩小仓库直接删除。

## 检查

```sh
node tools/check-public.mjs
node tools/check-public.mjs --history
./gradlew -PabiFilter=arm64-v8a testDebugUnitTest --tests 'com.huangder.lumibooks.data.sync.MyBooks*' --tests 'com.huangder.lumibooks.data.local.database.*' assembleDebug assembleDebugAndroidTest
```

公开检查仅覆盖部分私密文件名和令牌/私钥模式；忽略二进制及大文件内容，不能替代人工审查。
历史模式检查本地 Git 引用，不覆盖未拉取的远端对象，也不会重写历史。
定向测试不代表全部单测通过；本分支全量单测曾有渲染失败及 Markdown 原生渲染挂起，需单独排查。
CI 运行上述定向回归及 Debug 构建，不安装到设备；实际安装、视觉与恢复测试由维护者执行。

## 发布

版本号保持 2.3.10 / 2310，除非维护者明确要求调整。
APK 作为 Release 附件分发，源码不继续跟踪 dist 或构建目录。
同版本的不同构建用源码提交号与 APK SHA-256 识别，提供准确对应的源码。
保留 [LICENSE](../LICENSE)、[NOTICE](../NOTICE.md) 与第三方声明。
GitHub 上先启用私密漏洞报告，再确认 SECURITY.md 的入口。
编译成功不表示已发布、已推送或已更新线上服务器。
