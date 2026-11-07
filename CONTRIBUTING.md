# 参与 My books 安卓改版

本仓库基于 [Huangder/Lumi_Books](https://github.com/huangder/Lumi_Books)。
保留根目录 LICENSE、上游署名和 app/src/main/assets/licenses 中的第三方声明。
你提交的代码与资源应具有可提交和分发的权利，不要把全部既有代码署名改成自己。

本分支的实际环境与构建见 [改版指南](devdocs/mybooks-open-source.md)，
UI 必须遵循 [现行 UI 与动效规范](devdocs/lumi-ui-motion-design-system.md)。
构建环境以改版指南与当前项目配置为准。

提交前请执行：

```sh
node tools/check-public.mjs
./gradlew -PabiFilter=arm64-v8a testDebugUnitTest --tests 'com.huangder.lumibooks.data.sync.MyBooks*' --tests 'com.huangder.lumibooks.data.local.database.*' assembleDebug assembleDebugAndroidTest
```

这是同步及数据库迁移的定向回归，不代表整个单元测试套件通过。
修改其他模块时应运行相关检查；PR 写明实际结果与未覆盖范围。
数据库迁移只追加，保留阅读进度、文件、标注和排队修改；不得通过清库规避迁移问题。
单用户、多设备是当前范围；版本号由维护者明确决定，日常贡献不要自行递增。

Bug 请提供版本/提交、Android 版本、步骤和脱敏日志；PR 说明行为变化、验证与兼容影响。
不要提交电子书、备份、服务器账号、Cookie、令牌、local.properties 或签名密钥。
安全问题按 [SECURITY.md](SECURITY.md) 私下报告。
