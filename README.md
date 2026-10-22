# GDrive Backup

NeoForge 1.21.1 サーバーのワールドを、Google Drive へ定期的にバックアップするサーバー専用 Mod です。
クライアント側への導入は不要です。

## 特徴

- **整合性を意識したスナップショット**
  - 本コピーは saving を有効にしたまま実行（チャンクのアンロードは通常どおり）
  - 最後に `save-off` → `save-all flush` → 差分再コピー → `save-on`
  - `save-off` 中はチャンクがアンロードされないため、この時間を最短に抑えてメモリ急増を防ぐ
  - 書き込み途中の region ファイルは、変更検出後に再コピーして補正する
- **Google Drive ネイティブ連携**
  - OAuth 2.0 Device Flow（ヘッドレスサーバー対応）
  - 最小権限の `drive.file` スコープ
  - 16 MiB 単位の resumable upload、429/5xx リトライ、MD5/サイズ検証
- **世代管理**
  - ローカル ZIP と Google Drive の両方に保持数を設定可能
  - アップロード失敗時はローカル ZIP を残し、次回起動時に再送
- **Docker 対応**
  - `itzg/minecraft-server` の `/data` 構成を想定
- **復元用ダウンロード**
  - `/gdrivebackup download <name>` で MD5 検証付きダウンロード

## 動作環境

- Minecraft 1.21.1
- NeoForge 21.1.x（開発・検証: 21.1.264）
- Java 21
- サーバー専用（クライアント Mod 不要）

## ビルド

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew build
```

生成物:

```
build/libs/gdrivebackup-1.0.1.jar
```

テスト:

```bash
./gradlew test
```

## インストール

1. `gdrivebackup-1.0.1.jar` をサーバーの `mods/` に置く
2. サーバーを一度起動する
3. `config/gdrivebackup-common.toml` が生成される
4. Google Drive を使う場合は [Google Drive セットアップ](docs/google-drive-setup.md) を行う
5. `/gdrivebackup auth` で認証
6. `/gdrivebackup now first-test` でテストバックアップ

Docker の場合は `mods/` ではなく、マウントしている `data/mods/` に jar を置きます。

## Google Drive の設定

初回のみ、利用者自身の Google Cloud プロジェクトで OAuth クライアントを作成します。

1. Google Drive API を有効化
2. OAuth 同意画面に `https://www.googleapis.com/auth/drive.file` を追加
3. アプリを **Production** に公開（Testing のままだとトークンが7日で失効）
4. アプリ種類 **TVs and Limited Input devices** で OAuth クライアントを作成
5. JSON を `config/gdrivebackup/client_secret.json` に配置
6. `/gdrivebackup auth` を実行し、表示された URL とコードで認証

詳細: [docs/google-drive-setup.md](docs/google-drive-setup.md)

## 設定

設定ファイル: `config/gdrivebackup-common.toml`

### `[general]`

| キー | デフォルト | 説明 |
|---|---|---|
| `enabled` | `true` | スケジュール実行を有効化 |
| `schedule` | `"0 4 * * *"` | 5フィールド cron または `6h` などの interval |
| `timezone` | `"Asia/Tokyo"` | スケジュールのタイムゾーン |
| `snapshot-mode` | `"online"` | `online`（ほぼ無停止）または `pause`（コピー中停止） |
| `server-name` | `""` | Drive フォルダ名。空ならワールド名 |

### `[backup]`

| キー | デフォルト | 説明 |
|---|---|---|
| `local-dir` | `"backups/gdrivebackup"` | staging と ZIP の保存先。Docker では `/backups/gdrivebackup` 推奨 |
| `include-mods` | `false` | `mods/` 本体を含める。false でも `mods-manifest.json` は含む |
| `include-config` | `true` | `config/` と `defaultconfigs/` を含める |
| `keep-local` | `2` | ローカルに残す ZIP 数 |
| `min-free-gb` | `5` | バックアップ後に確保する空き容量 |
| `compression-level` | `1` | ZIP 圧縮レベル（0〜9） |
| `max-freeze-seconds` | `45` | `save-off` 中の最大許容秒数。超えたらバックアップを中断して `save-on` に戻す。0で無効 |
| `run-missed` | `true` | 起動時に逃したスケジュールを実行 |
| `extra-paths` | `[]` | 追加で含めるサーバー配下のパス |
| `exclude-patterns` | `[]` | 追加除外 glob |

### `[drive]`

| キー | デフォルト | 説明 |
|---|---|---|
| `enabled` | `true` | Google Drive へアップロード |
| `require-upload` | `true` | アップロード失敗をバックアップ失敗として扱う |
| `folder` | `"MinecraftBackups"` | Drive のトップフォルダ |
| `client-secret-file` | `"config/gdrivebackup/client_secret.json"` | OAuth クライアント JSON |
| `credentials-file` | `"config/gdrivebackup/credentials.json"` | リフレッシュトークン保存先 |
| `chunk-size-mib` | `16` | resumable upload のチャンクサイズ |
| `max-retries` | `5` | 一時エラーのリトライ回数 |
| `keep-remote` | `7` | Drive に残す世代数 |

パスはサーバーディレクトリ基準です。絶対パスも使用できます。

## コマンド

OP レベル4以上、またはコンソール/RCON から実行できます。

| コマンド                            | 内容                      |
| ------------------------------- | ----------------------- |
| `/gdrivebackup status`          | 実行状況・前回・次回・エラー・Drive 状態 |
| `/gdrivebackup now [note]`      | 手動バックアップ                |
| `/gdrivebackup auth`            | Google Drive デバイス認証を開始  |
| `/gdrivebackup revoke`          | Google Drive 認証を取り消す    |
| `/gdrivebackup list`            | Drive 上のバックアップ一覧        |
| `/gdrivebackup verify [name]`   | Drive とローカルのサイズ/MD5 を検証 |
| `/gdrivebackup download <name>` | 復元用にダウンロード              |
| `/gdrivebackup reload`          | スケジュール設定を再読込            |

RCON では先頭の `/` は不要です。

## バックアップの流れ

1. ディスク容量と設定を確認
2. staging へ本コピー（saving 有効のまま。チャンクのアンロードも通常どおり）
3. `save-off` 相当（全 `ServerLevel.noSave = true`）＋ `saveEverything(flush=true, forced=true)`
4. 本コピー中に変更されたファイルとプレイヤーデータだけを短時間で再コピー
5. `save-on` を `finally` で必ず実行
6. ZIP 作成（SHA-256/MD5 を同時計算）
7. Google Drive へ resumable upload
8. ローカル/リモートの保持ポリシーを適用
9. 状態を `config/gdrivebackup/state.json` に保存

> 本コピー中はワールドが通常どおり書き換わるため、変更されたファイルは手順4で再コピーします。
> `save-off` は「フラッシュ＋差分補正」の短時間だけなので、チャンクがアンロードされずに
> メモリが増え続ける時間を最小化しています。大人数で探索中のバックアップでは差分が増え、
> 手順4が長くなることがあります。`max-freeze-seconds`（デフォルト45秒）を超えた場合は
> バックアップを中断して `save-on` に戻し、サーバーを守ります。

### バックアップ対象

- `level-name` のワールドフォルダ全体（DIM-1、DIM1、dimensions を含む）
- `server.properties`
- `ops.json`、`whitelist.json`、`banned-*.json`、`usercache.json`
- `config/`、`defaultconfigs/`
- 任意で `mods/`
- 追加の `extra-paths`

除外:

- `logs/`、`crash-reports/`、`cache/`、`libraries/`、`versions/`
- `session.lock`
- `backups/`（バックアップ出力先）
- `config/gdrivebackup/`（認証情報・状態）

### 注意: 容量

ZIP は毎回フルバックアップです。5〜20GB のワールドでは、staging と ZIP の作成に
**ワールドサイズの約2倍 + 5GB** の空き容量を見込んでください。

Docker では `backups` を専用ボリュームにしてください。

## Docker

`docker-compose.example.yml` を参照してください。

```bash
mkdir -p data/mods backups
cp build/libs/gdrivebackup-1.0.1.jar data/mods/
docker compose up -d
```

`data/config/gdrivebackup-common.toml` の `local-dir` を次のように変更します。

```toml
[backup]
local-dir = "/backups/gdrivebackup"
```

`client_secret.json` は `data/config/gdrivebackup/client_secret.json` に置きます。

認証コードは `docker logs -f minecraft-neoforge` で確認できます。
コマンドは RCON 経由で実行できます。

```bash
docker exec -it minecraft-neoforge rcon-cli
> gdrivebackup auth
> gdrivebackup status
```

5〜20GB のワールドを扱う場合、オンラインコピー中はチャンクがアンロードされないため、
コンテナのメモリに余裕を持たせてください（例: 8G 以上）。

## 復元

1. `/gdrivebackup list` で名前を確認
2. `/gdrivebackup download <name>`
3. サーバーを停止
4. 現在のワールドを退避
5. ZIP をサーバーディレクトリに展開
6. 起動確認

詳細: [docs/restore.md](docs/restore.md)

## セキュリティ

- `drive.file` のみ使用。Mod が作成したファイル以外はアクセスできません。
- `credentials.json` は POSIX 環境で `0600` に設定。
- `config/gdrivebackup/` はバックアップ ZIP から除外。
- OAuth クライアントとトークンは利用者のサーバー上にのみ保存されます。
- 認証取消は `/gdrivebackup revoke` または Google アカウント設定から。

## 開発・検証状況

詳細: [TEST-REPORT.md](TEST-REPORT.md)

- `./gradlew test`: 24テスト（cron、保持、ZIP、コピー整合性、freeze guard、resumable upload、OAuth Device Flow/更新、保留アップロード、MD5形式）
- Docker本番相当環境（NeoForge 21.1.264 / Java 21）で以下を確認
  - Bot負荷中の763MBワールド: バックアップ成功、メモリ約5.1GBで安定、freeze約5秒
  - `max-freeze-seconds` 超過時にバックアップを中断し、サーバーはhealthy継続
  - 719MB ZIPを別コンテナへ復元し、起動・再バックアップに成功
  - 2分間隔のスケジュール実行に成功
- ローカル `runServer` でもバックアップ ZIP の作成・展開検証を実施
- Google Drive アップロードは fake HTTP サーバーで 308 レジュームと MD5 検証を実施
- **実際の Google Drive での OAuth/アップロードは、利用者の認証情報が必要なため未実施**
  - 初回は `/gdrivebackup now` で必ずテストしてください

## 制限・今後の予定

- フル ZIP のみ（差分・重複排除なし）
- バックアップの暗号化は未対応（必要なら rclone crypt 等を併用）
- 稼働中の自動復元は未対応（ダウンロード + 手動復元）
- サービスアカウント / Shared Drive は未対応
- `pause` モードは巨大ワールドで watchdog に掛かる可能性あり。通常は `online` を使用
- NeoForge 1.21.1 のみ

## ライセンス

MIT
