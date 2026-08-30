# Comic Explorer

広告・課金・アカウント・ネットワーク機能を持たない、Android向けのローカル漫画ビューワです。

APKは[Releases](https://github.com/s-deme/comic_explorer_portable/releases/latest)からダウンロードできます。

- SAF（システムのフォルダ選択）で選んだフォルダだけを読む
- PDF、ZIP / CBZ、画像フォルダを閲覧
- JPG、PNG、GIF、BMP、WebP、AVIFを表示
- 単一画像・PDF・CBZのページ位置・しおり・お気に入りを端末内に保存
- 検索、最近開いた作品、並べ替え、画像サムネイル
- ピンチ／ダブルタップズーム、タップ／スワイプ操作、ページスライダー、全画面、自動送り
- 読書方向、表示フィット、明るさ、色反転、回転、任意の音量キーページ送りを設定

対応OSは Android 10（API 29）以降です。

RAR / CBR、7z、SMB / FTP、Google Drive同期、広告、課金、分析・通知SDKは意図的に実装していません。

改善項目と実装状況は [PRODUCT_IMPROVEMENTS.md](PRODUCT_IMPROVEMENTS.md) を参照してください。

## APKを作る

Android SDK（platforms/android-35 と build-tools/36.0.0）および JDK 21を用意して実行します。`build.ps1`は`ANDROID_SDK_ROOT`（次に`ANDROID_HOME`）と`JAVA_HOME`を優先し、Windowsでは一般的なインストール先も探索します。

```powershell
./build.ps1 -Configuration Debug
```

出力先は既定で `dist/comic-explorer.apk` です。`-OutputPath`で変更できます。Debugビルドでは、初回だけローカル署名用のデバッグキーストアをプロジェクト直下（Git管理外）に生成します。

Releaseビルドは固定された署名鍵を必要とし、次の環境変数が不足している場合はデバッグ鍵へフォールバックせず失敗します。

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

ビルド後はAPK署名と、不要なAndroid権限が含まれていないことを自動検証します。

## CIと自動Release

GitHub Actionsは用途を分離しています。

- `main`へのpushとPull Request: Debug APKをビルド・検証し、コミットSHAを含むWorkflow Artifactとして14日間保存します。正式Releaseは作成しません。
- `v*`タグのpush: 固定鍵で署名したAPKをビルドし、APKとSHA-256ファイルを新しい正式Releaseへ添付します。

Release処理は、タグと `AndroidManifest.xml` の `versionName` が一致しない場合、`versionCode`が正の整数でない場合、同じタグのReleaseが存在する場合、または署名設定が不足している場合に失敗します。既存のReleaseやタグは上書きしません。

### GitHub Secrets

リポジトリの `Settings` → `Secrets and variables` → `Actions` に次を登録します。

- `ANDROID_KEYSTORE_BASE64`: リリース用keystoreをBase64化した内容
- `ANDROID_KEYSTORE_PASSWORD`: keystoreのパスワード
- `ANDROID_KEY_ALIAS`: 署名鍵のエイリアス
- `ANDROID_KEY_PASSWORD`: 署名鍵のパスワード

Base64は暗号化ではありません。値やkeystoreをリポジトリ、Issue、ログへ保存せず、GitHub Secretとして登録してください。GitHub上のSecretとは別に、keystoreと復旧情報を安全なオフライン領域へバックアップしてください。署名鍵を失うと、既に配布したAPKを同じアプリとして更新できません。既存ユーザーへ更新を配布する場合は、現在公開中のAPKと同じ鍵を使用する必要があります。

### v1.1.3を公開する例

まず `AndroidManifest.xml` の `versionName` を `1.1.3`、`versionCode`を以前より大きい整数へ更新し、通常の変更として検証・commit・pushします。その後にタグを作成します。

```powershell
git switch main
git pull --ff-only
./build.ps1 -Configuration Debug
git add AndroidManifest.xml
git commit -m "Prepare v1.1.3"
git push origin main
git tag -a v1.1.3 -m "Comic Explorer v1.1.3"
git push origin v1.1.3
```

最後のタグpushによってReleaseワークフローが起動します。既存の `v1.1.1` / `v1.1.2` タグは移動させません。
