# My books Android

Lumi をベースにした Android 電子書籍リーダー。ローカル読書、WebDAV、自分で運用する My books 書庫に対応します。

[中文](README.md) · [English](README.en.md) · [한국어](README.ko.md)

## 主な機能

- EPUB・PDF・TXT・MOBI・CBZ の閲覧と書庫管理。
- ハイライト、メモ、しおり、読み上げ、読書統計。
- WebDAV 同期、ローカルバックアップと復元。
- 自前の書庫へのログイン、書誌情報の同期、競合の解決。

## 利用と開発

APK は本リポジトリの Releases から取得し、自分のサーバーを設定してください。
[接続・ビルド手順](devdocs/mybooks-open-source.md) · [同期仕様](devdocs/mybooks-sync-protocol.md) · [貢献ガイド](CONTRIBUTING.md)

自前のバックエンドは読書位置・しおり・注釈の同期には未対応です。WebDAV 同期は別機能です。

## 出典とライセンス

[Huangder/Lumi_Books](https://github.com/huangder/Lumi_Books) を基にしています。© 2026 Huangder and contributors.
[GPLv3](LICENSE) と[第三者のライセンス表記](NOTICE.md)を保持しています。
