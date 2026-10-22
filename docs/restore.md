# バックアップからの復元

バックアップは ZIP 形式で、サーバーディレクトリを基準にした相対パスで保存されます。

```
world/
server.properties
ops.json
whitelist.json
config/
defaultconfigs/
backup-info.json
mods-manifest.json
```

## 1. バックアップ一覧を確認

```
gdrivebackup list
```

## 2. ダウンロード

```
gdrivebackup download minecraft-backup-20261022-040000-scheduled.zip
```

ダウンロード先:

```
<local-dir>/restore/<ファイル名>
```

デフォルトの `local-dir` は `backups/gdrivebackup` です。
Docker では設定に応じて `/backups/gdrivebackup/restore/` などになります。

ダウンロード時に Google Drive の MD5 と照合します。

## 3. 検証

```
gdrivebackup verify minecraft-backup-20261022-040000-scheduled.zip
```

ZIP の展開テストも行います。

```bash
unzip -t /path/to/backup.zip
```

## 4. 復元手順

> 必ずサーバーを停止してから行ってください。稼働中の自動置換は行いません。

1. サーバーを停止
2. 現在のワールドを退避
   ```bash
   mv world world.before-restore-20261022
   ```
3. ZIP をサーバーディレクトリへ展開
   ```bash
   unzip /path/to/backup.zip -d /path/to/server
   ```
4. 必要に応じて `server.properties` や `config/` を確認
   - 既存設定を上書きしたくない場合は、ZIP を別ディレクトリに展開して必要なファイルだけコピーしてください。
5. `mods-manifest.json` と現在の `mods/` を比較
   - バックアップ時と同じ Mod バージョンで起動するのが安全です。
   - `include-mods = true` にしていれば `mods/` も ZIP に含まれます。
6. サーバーを起動し、ワールドが読み込めることを確認

## Docker の場合

```bash
docker compose stop minecraft
mv data/world data/world.before-restore-20261022
unzip /path/to/backup.zip -d data
docker compose start minecraft
```

`data` は `docker-compose.example.yml` の `./data:/data` に対応します。

## 復元テストの習慣

バックアップは「復元できて初めてバックアップ」です。
月に一度、別ディレクトリのテストサーバーで復元し、ワールドが起動することを確認してください。

- 本番サーバーのワールドを直接上書きしない
- テスト用のポート・ディレクトリを使う
- `backup-info.json` の `minecraftVersion` / `neoForgeVersion` を確認する
