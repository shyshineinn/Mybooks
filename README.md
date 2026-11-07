# Mybooks Android

基于 Lumi 的安卓阅读客户端，支持本地阅读、WebDAV 与自部署 Mybooks 书库。

[English](README.en.md) · [日本語](README.ja.md) · [한국어](README.ko.md)

## 功能

- EPUB、PDF、TXT、MOBI、CBZ 阅读及书架管理。
- 高亮、笔记、书签、听书与阅读统计。
- WebDAV 同步、本地备份与恢复。
- 自建书库登录、书目同步与冲突处理。

## 使用与开发

安装包在本仓库 Releases 获取，服务器由使用者自行配置。
[连接与构建指南](devdocs/mybooks-open-source.md) · [同步协议](devdocs/mybooks-sync-protocol.md) · [参与贡献](CONTRIBUTING.md)

自建后端支持增量同步、安卓阅读数据同步与正文断点续传；WebDAV 同步独立提供。

## 来源与许可证

基于 [Huangder/Lumi_Books](https://github.com/huangder/Lumi_Books)，© 2026 Huangder 与贡献者。
保留 [GPLv3](LICENSE) 及[第三方声明](NOTICE.md)。
