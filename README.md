# Comic Explorer for Android

広告・課金・アカウント・ネットワーク機能を持たない、Android向けのローカル漫画ビューワです。

このリポジトリはAndroid版です。Windowsデスクトップ版の `comic_explorer` とは、配布物、対応形式、保存方式が異なります。

APKは[Releases](https://github.com/s-deme/comic_explorer_portable/releases/latest)からダウンロードできます。

- SAF（システムのフォルダ選択）で選んだフォルダだけを読む
- PDF、ZIP / CBZ、画像フォルダを閲覧
- JPG、PNG、GIF、BMP、WebPを表示。AVIFは端末のAndroid画像デコーダーが対応する場合に表示
- 単一画像・PDF・CBZのページ位置・しおり・お気に入りを端末内に保存
- ComicScreen風の暗色UI、ストレージ／ディレクトリ／履歴／しおりタブ、お気に入り、リスト／2〜4列グリッド表示
- 検索、最大100件の詳細な読書履歴、期間指定消去、並べ替え、画像サムネイル、外部アプリの「開く」連携
- 単ページ／見開き、横／縦スワイプ、ピンチ／設定可能なダブルタップズーム、ページスライダー、全画面、自動送り
- 読書方向、4種の表示フィット、明るさ、色反転、5種の画像フィルター、回転、音量キー操作を設定
- しおり一覧とメモ、サイズ／透明度を変えられる画面上ページボタン、UTF-8／Shift_JISのZIPファイル名

対応OSは Android 10（API 29）以降です。

AVIFのデコード可否はOSと端末実装に依存し、特にAndroid 10 / 11では対応を保証しません。GIFは静止画像として表示し、アニメーション再生は行いません。

RAR / CBR、7z、SMB / FTP、Google Drive同期、ファイル削除・移動、広告、課金、分析・通知SDKは実装していません。

改善項目と実装状況は [PRODUCT_IMPROVEMENTS.md](PRODUCT_IMPROVEMENTS.md) を参照してください。

## 実装済みと検証済みの区別

`PRODUCT_IMPROVEMENTS.md` のチェックは、対応する実装がソースに存在することを示します。[`VISUAL_ACCESSIBILITY_AUDIT.md`](VISUAL_ACCESSIBILITY_AUDIT.md) はスタイルと画面構成の設計監査です。どちらも、すべてのAndroid端末での実操作、形式別デコード、TalkBack、画面回転、メモリ負荷を自動的に検証したことまでは意味しません。

`build.ps1` が自動確認する範囲は、Gradleコンパイル、APK署名、不要なAndroid権限がないことです。Release判定では、別途、実端末またはエミュレーターのOS/API、端末名、確認日、対象APKを記録してください。

## Android Studioで動かす

このリポジトリは標準のGradle Androidプロジェクトです。Android Studioでこのフォルダを開き、Gradle同期の完了後に実行構成`app`と起動済みのエミュレーターを選んで、上部の`▶ Run`を押します。ビルド、インストール、起動がまとめて実行されます。

PowerShellから同じDebug版をエミュレーターへ入れる場合は、エミュレーターを起動してから次を実行します。

```powershell
.\gradlew.bat installDebug
```

## APKを作る

Android SDK（platforms/android-35 と build-tools/36.0.0）および JDK 21を用意して実行します。Android Studioからの`▶ Run`、またはGradle WrapperでDebug版をビルドできます。配布用APKを既定の`dist`へコピーし、署名と権限も確認する場合は次を実行します。

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

### 手動確認の最小項目

- Android 10と、現在サポートする新しいAndroid版で起動できる
- SAFで選択した範囲だけを参照し、許可の再起動後保持と失効時表示が正しい
- JPG、PNG、GIF、BMP、WebP、PDF、ZIP / CBZを開ける
- AVIF対応端末ではAVIFを開け、非対応端末では復帰可能なエラーになる
- ページ位置、しおり、お気に入り、最近開いた作品が再起動後も復元される
- TalkBack、文字拡大、縦横画面、明暗の異なる表示条件で主要操作へ到達できる

## CIと自動Release

GitHub Actionsは用途を分離しています。

- `main`へのpushとPull Request: Debug APKをビルド・検証し、コミットSHAを含むWorkflow Artifactとして14日間保存します。正式Releaseは作成しません。
- `v*`タグのpush: 固定鍵で署名したAPKをビルドし、APKとSHA-256ファイルを新しい正式Releaseへ添付します。

Release処理は、`gradle.properties` の `comicExplorerVersionName` とタグが一致しない場合、`comicExplorerVersionCode`が正の整数でない場合、同じタグのReleaseが存在する場合、または署名設定が不足している場合に失敗します。既存のReleaseやタグは上書きしません。

### GitHub Secrets

リポジトリの `Settings` → `Secrets and variables` → `Actions` に次を登録します。

- `ANDROID_KEYSTORE_BASE64`: リリース用keystoreをBase64化した内容
- `ANDROID_KEYSTORE_PASSWORD`: keystoreのパスワード
- `ANDROID_KEY_ALIAS`: 署名鍵のエイリアス
- `ANDROID_KEY_PASSWORD`: 署名鍵のパスワード

Base64は暗号化ではありません。値やkeystoreをリポジトリ、Issue、ログへ保存せず、GitHub Secretとして登録してください。GitHub上のSecretとは別に、keystoreと復旧情報を安全なオフライン領域へバックアップしてください。署名鍵を失うと、既に配布したAPKを同じアプリとして更新できません。既存ユーザーへ更新を配布する場合は、現在公開中のAPKと同じ鍵を使用する必要があります。

### v1.1.3を公開する例

まず `gradle.properties` の `comicExplorerVersionName` を `1.1.3`、`comicExplorerVersionCode`を以前より大きい整数へ更新し、通常の変更として検証・commit・pushします。その後にタグを作成します。

```powershell
git switch main
git pull --ff-only
./build.ps1 -Configuration Debug
git add gradle.properties
git commit -m "Prepare v1.1.3"
git push origin main
git tag -a v1.1.3 -m "Comic Explorer v1.1.3"
git push origin v1.1.3
```

最後のタグpushによってReleaseワークフローが起動します。既存の `v1.1.1` / `v1.1.2` タグは移動させません。
