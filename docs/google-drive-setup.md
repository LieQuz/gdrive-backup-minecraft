# Google Drive 連携セットアップ

GDrive Backup は、利用者自身の Google Cloud プロジェクトに OAuth クライアントを作成し、
`drive.file` スコープだけを使って Google Drive にバックアップをアップロードします。

初回のみブラウザでの認証が必要です。サーバーにブラウザは不要です。

## 必要なもの

- Google アカウント
- 初回認証用のブラウザ（PC・スマートフォン可）
- サーバーの `config/gdrivebackup/` にファイルを置ける権限

## 1. Google Cloud プロジェクトを作成

1. <https://console.cloud.google.com/> を開く
2. 上部のプロジェクト選択から「新しいプロジェクト」を作成
3. 作成したプロジェクトを選択

## 2. Google Drive API を有効化

1. 「API とサービス」→「ライブラリ」
2. `Google Drive API` を検索して選択
3. 「有効にする」をクリック

## 3. OAuth 同意画面を設定

1. 「API とサービス」→「OAuth 同意画面」
2. User Type:
   - 個人の Google アカウント: **External**
   - Google Workspace: **Internal**（組織内のみ）
3. アプリ名・サポートメール・開発者連絡先を入力
4. スコープに次を追加:
   ```
   https://www.googleapis.com/auth/drive.file
   ```
5. External の場合、テストユーザーに自分の Google アカウントを追加
6. **「アプリを公開」で Production に変更**

> **重要:** OAuth 同意画面が「Testing」のままだと、Google の仕様により
> リフレッシュトークンが **7日で失効** します。個人利用でも Production にしてください。
> `drive.file` は非センシティブスコープのため、CASA などの重い審査は不要です。
> 未確認アプリの警告が出る場合がありますが、自分のプロジェクトであれば問題ありません。

## 4. OAuth クライアントを作成

1. 「API とサービス」→「認証情報」
2. 「認証情報を作成」→「OAuth クライアント ID」
3. アプリケーションの種類: **TVs and Limited Input devices**
   - 「デスクトップアプリ」ではない点に注意してください。
4. 名前を付けて作成
5. JSON をダウンロード

## 5. サーバーに配置

ダウンロードした JSON を次の場所に置きます（サーバーディレクトリ基準）。

```
config/gdrivebackup/client_secret.json
```

Docker の例:

```text
./data/config/gdrivebackup/client_secret.json
```

ファイル名は設定 `drive.client-secret-file` で変更できます。

## 6. デバイス認証

サーバーを起動し、コンソールまたは RCON から実行します。

```
gdrivebackup auth
```

コンソールに次のような表示が出ます。

```
Open https://www.google.com/device and enter code XXXX-XXXX to authorize Google Drive
```

1. 表示された URL をブラウザで開く
2. コードを入力
3. バックアップに使う Google アカウントで許可

完了すると `config/gdrivebackup/credentials.json` が作成されます。
このファイルにはリフレッシュトークンが入るため、**絶対に共有しないでください**。
Mod はこのファイルをバックアップ ZIP から除外し、POSIX 環境では `0600` に設定します。

## 7. 動作確認

```
gdrivebackup status
gdrivebackup now first-test
gdrivebackup list
```

`Drive: authorized` と表示され、`list` に `first-test` の ZIP が現れれば成功です。
Google Drive の `MinecraftBackups/<サーバー名>-<ID>/` フォルダにも表示されます。

## トラブルシューティング

| 症状 | 確認すること |
|---|---|
| `invalid_client` | OAuth クライアントの種類が「TVs and Limited Input devices」か。JSON が正しいか |
| `invalid_scope` | 同意画面に `drive.file` を追加したか。コード側は `drive.file` のみ使用 |
| `redirect_uri_mismatch` | 「デスクトップアプリ」で作成していないか。TV/限定入力デバイスで作り直す |
| `access_denied` | テストユーザーに自分のアカウントを追加したか。Production 公開後か |
| 7日ほどで認証が切れる | 同意画面が Testing のまま。Production に公開する |
| `storageQuotaExceeded` | Google Drive の容量不足。`keep-remote` を減らすか不要なファイルを削除 |
| `insufficientPermissions` | `/gdrivebackup auth` で再認証する |
| コンソールに URL が出ない | `docker logs -f` でログを確認。`gdrivebackup status` で Drive が有効か確認 |
| `The OAuth client was not found` | `client_secret.json` が別プロジェクトのものではないか |

## 認証の取消

```
gdrivebackup revoke
```

または Google アカウントの「サードパーティ製アプリとサービス」からアクセスを取り消せます。

## セキュリティ

- 使用スコープは `drive.file` のみです。Mod が作成したファイル以外は見えません。
- 認証情報はサーバー上にのみ保存されます。開発者や第三者のサーバーへ送信しません。
- `credentials.json` と `client_secret.json` は Git や配布物に含めないでください。
- バックアップ ZIP には `config/gdrivebackup/` 配下を含めません。
