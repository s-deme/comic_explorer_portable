# 開発・検証・リリース手順

2026-09-20の構造リファクタリング6件の採否・実装・検証結果は [REFACTORING.md](REFACTORING.md) を参照。

## 変更に応じた最小限の検証

通常は変更に関係する分野だけを実行する。アイコン・文言の変更はビルドと変更画面の確認、配色変更は `themes` と代表的な明暗画面、閲覧操作・設定反映は `reader`、保存・同期・転送は `data`、FTPは `network`、追加形式のデコードは `formats` を選ぶ。複数分野に影響する共通処理や公開前は全件とLintを実行する。成功後の再実行は、新たな変更・失敗・未解決の懸念がある場合に限る。名称整理だけなら撮影不要。

テストコードを変えたときは `:app:assembleDebugAndroidTest`、アプリを変えたときは `:app:assembleDebug` も実行し、対応する検証用APKをインストールする（どちらも `-PcomicExplorerValidation=true`）。新しいテスト基盤や依存ライブラリは不要。

```powershell
# テーマだけ。他の分野は suite の値を置き換える。
adb shell am instrument -w -e suite themes jp.yaman.comicexplorer.validation.test/jp.yaman.comicexplorer.ParityInstrumentation
# 全件。suite を省略しても全件実行。
adb shell am instrument -w -e suite all jp.yaman.comicexplorer.validation.test/jp.yaman.comicexplorer.ParityInstrumentation
```

| suite | 範囲 |
|---|---|
| `themes` | 旧ライト／ダークID、9テーマの配色、不明ID、選択・保存・再生成・キャンセル |
| `reader` | 画像補正、ページ境界、ZIP／PDF、ジェスチャー、閲覧設定、切り抜き、関連する表示不具合 |
| `data` | 読書情報、同期競合、設定インポート、キャッシュ、アルバム、転送と失敗時の原本保持 |
| `network` | パス境界、ローカルの模擬FTPサーバーとの通信 |
| `formats` | RAR・7z・分割／暗号化アーカイブ・アニメーション等の追加形式 |

各分野は検証専用アプリの設定を初期化して独立実行する。通常版アプリでは実行を拒否し、未知のsuiteも初期化前に拒否する。終了コードだけでなく出力の `PASS [分野]` / `FAIL [分野]` を確認する。形式判定・自然順ソートを変更した場合は、既存のCLIテスト `tests/ComicFileTest.java` も実行する。

テーマのスタイル定数を丸写しした10件の照合は旧IDの互換性2件へ縮小した。配色・データ保全・入力境界・既知の不具合の検証は維持する。

分割後の単独実行を確認済み: themes 14件、data 37件、network 5件、formats 18件、reader 41件（合計115件）。不正なsuiteの拒否も確認した。reader単独実行ではUI自動操作の接続を画面生成前に明示し、先行する分野への依存を除いた。記録は `build/minimal-test-build.txt` と `build/minimal-test-*.txt`。今回変更したのはテストと手順書だけのため、画面撮影・Lint・通過した分野の繰り返し実行は行わない。

## 2026-09-20 UI・閲覧設定のリファクタリング

色の明暗別エイリアスを背景・面・文字・強調色の役割名へ統合。異なる背景を使うボタンスタイルは維持し、同一だったPRIMARY系のみ統合した。テーマは保存ID・表示名・スタイルを一組で定義し、既存ID 0〜9を維持する。アイコン生成と状態更新をUiへ集約し、起動／復帰時の閲覧設定反映を共通化した。起動時の全画面設定は復帰時には適用しない。ページ構成・スクロール・フィット・明るさ・画面回転・方向の6ダイアログをReaderOptionsへ移した。

回帰テスト123チェック成功（テーマID互換性、9テーマの配色、設定画面から戻った際の反映を含む）。その後の不要な明暗引数／旧メソッド名整理を含む最終ビルド・Lint成功、0 errors / 40 warnings。最終APKのライト／青の閲覧画面と青の本棚を目視確認。記録: `build/refactor-test.txt`、`build/refactor-final-build.txt`、`build/refactor-*.png`。通常版・配布用APKは未更新。

Comic Explorer for Android の開発者向け情報です。アプリの使い方は [README.md](README.md) を参照してください。

改善項目と実装状況は [PRODUCT_IMPROVEMENTS.md](PRODUCT_IMPROVEMENTS.md) を参照してください。

ComicScreen対応の最新範囲と未検証箇所は [COMICSCREEN_IMPLEMENTATION.md](COMICSCREEN_IMPLEMENTATION.md) に記載しています。

## 2026-09-20 9種類のテーマ

設定に色見本付きのテーマ選択を追加。ライト・ダーク・赤・青・緑・紫・ピンク・オレンジ・グレーと「システムに合わせる」を選べます。通常は3列で、文字拡大や狭い画面では列数を減らします。選択は即時保存・反映し、既存のシステム／ライト／ダークの保存値は維持します。

配色の定義元をAndroidのテーマリソースへ集約し、アプリ側の共通色も同じリソースから取得。標準ウィジェット、設定、ライブラリ、読書メニュー、ページ一覧へ反映します。色付きテーマは暗い無彩色の背景を基本とし、原稿画像の処理・色は変更しません。グリッド背景を個別指定している場合はその設定を保持します。

検証: テーマ選択・保存・再生成・キャンセルと9テーマのコントラスト／ネイティブアクセント一致を含むInstrumentation **110チェック成功**。最終APKビルド成功、Lint **0 errors / 40 warnings**、XMLの実配色から生成した**63組のコントラスト検査成功**。ライト／赤の選択画面、文字1.5倍の2列表示、青の設定・ライブラリ（空状態）・読書画面を目視確認しました。TalkBack実操作・全言語の画面確認・Android 10実機は未検証です。記録は `build/themes-build.txt`、`build/themes-test.txt`、`build/theme-contrast.txt`、`build/theme-*.png`。配布用APKは未更新です。

## 2026-09-20 漫画向けの初期送り方向

未設定時の送り方向を左送り（RTL）へ変更。スワイプ・ページボタン・見開きの並びは既存の共通設定を使用し、明示的に保存された方向は保持します。初期値と右送り設定の保持を検証に追加し、ビルド成功・Instrumentation **98チェック成功**。記録は `build/manga-default-build.txt` と `build/manga-default-test.txt`。配布APKは未更新です。

## 2026-09-20 読書メニューの整理

右上の「⋮」から5分類を直接開く構成へ変更し、3ページ式ツールバーと切替ジェスチャーを廃止しました。主要操作は1段に固定。反転は画像フィルターへ、メモ編集はしおりへ、共通の余白割合と本別トリミングは適用範囲を明記して集約しています。送り方向とスクロール方式は独立して変更でき、読書位置リセットには確認を挟みます。既存の設定キー・保存データ・キー割当は維持しています。

明るいテーマの読書ツールバーにも適切なアイコン色を適用し、システムバー余白はDrawerLayout内のFrameLayoutへ適用するよう修正しました。回帰テストには、メニュー経由の送り方向変更が縦スクロールを維持することと、リセットのキャンセルで表示・保存位置が変わらないことを追加しています。

検証: 最終APK・テストAPKのビルド成功、Instrumentation **96チェック成功**、Lint **0 errors / 40 warnings**。エミュレーターで明るいテーマの縦・横向き、文字1.5倍のメニュー、暗いテーマの横向きメニューを目視確認。記録は `build/reader-menu-build.txt`、`build/reader-menu-test.txt`、`build/reader-menu*.png`。通常版アプリと `dist/comic-explorer.apk` は更新していません。TalkBack実操作は未検証です。文字1.5倍では既存の下部ページ番号欄が折り返して欠けるため、メニュー外の表示課題として残っています。

## 2026-09-18 リファクタリング5回

各回で呼び出し元と既存実装を確認し、次の案を採用・実装しました。1〜4回目は各回のコンパイル、5回目は全変更を含むビルド・Lint・回帰テストで確認しました。

| 回 | 採用案と実装 |
|---|---|
| 1 | 読書画面の不要なデコードラッパーを削除し、キャッシュの有無で重複していた表示・位置保存・先読みを共通化。 |
| 2 | アルバムの操作は1回読み出したJSON上で更新。参照先の移動を、削除・追加ごとの保存から最後の1回の保存へ変更。 |
| 3 | 23組の設定読み書きを既存の `number`・`enabled`・`put` へ統一。キー・既定値・範囲制限は維持。 |
| 4 | PDFと見開きの縮小率を `PageSource.bitmapScale` へ集約。PDFページの解放をtry-with-resourcesへ変更。 |
| 5 | URI識別子と同期ファイルのSHA-256計算を、既存依存Commons Codecの `DigestUtils` に統一。 |

追加の回帰チェックは、アルバム移動先との重複防止と既存URIハッシュの互換性の2件です。外部サービスへの実接続はこのリファクタリングの検証に含めません。

結果: 検証APK・テストAPKのビルド成功、API 37・16 KiBの検証専用アプリで **94チェック成功**、Lint **0 errors / 46 warnings**（警告数は従来と同じ）。記録は `build/refactor-pass1.txt` 〜 `build/refactor-pass5.txt` と `build/refactor-five-test.txt`。配布用の `dist/comic-explorer.apk` は今回更新していません。

## 2026-09-18 テストの最小化

自動テストは、データ保全・入力境界・形式ごとの読み込み経路・既知の表示／操作不具合を検出するものに絞ります。撮影できたことだけを確認する画面巡回、翻訳やテーマの定数照合、別の操作テストで結果を確認できる重複を削除しました。起動待ち・準備処理は失敗時に停止しますが、成功件数には含めません。画面の見た目・文字拡大・各言語の配置は、該当UIを変更したときの目視確認とします。

`ViewerActivity.main` 内の未実行チェックは削除し、固有のページ境界・自動見開き・画像サイズ制限をInstrumentationへ統合しました。フィルター更新は内部カウンターと子Viewの存在から、表示された画素の変化を確認する1件へ置き換えています。形式判定・自然順ソートの26チェックは、短い表形式で異なる拡張子・MIME・桁あふれ・比較の反対称性を確認しているため維持します。転送失敗を再現するDocumentsProviderと小さな形式別フィクスチャも維持します。

検証: Pixel 7a / Android 17・API 37・16 KiB の `.validation` アプリで **92チェック成功**、CLIの形式判定・ソート **26チェック成功**。検証用APKとテストAPKのビルド成功。記録は `build/test-audit-build.txt` と `build/test-audit-result.txt`。テスト用コードを差し引き64行削減。旧142件には撮影と準備待ちも含むため、件数差は削除した機能範囲を意味しません。以下の過去の件数・撮影記録は実施時点の記録です。

## 2026-09-17 ComicScreenとの差分対応

追加実装と制限は [対応記録](COMICSCREEN_IMPLEMENTATION.md) を参照。形式判定・ソート24チェック、Android Instrumentation97チェックが成功。Lintは0 errors / 33 warnings。追加依存のライセンスは [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) に記載し、APKにも同梱しています。

Instrumentatonの追加テストは、アプリとは別のテストAPK内に保存先を用意し、コピー／移動・置換・失敗時の原本保持・フォルダ配下の読書情報移行を実行します。テスト用保存先にだけMANAGE_DOCUMENTSのshell権限を使用し、終了後に解除します。RAR4のフィクスチャは `python tests/create_rar_fixture.py` で再生成可能です。7zはテスト中にLZMA2アーカイブを生成します。

この環境ではJDK 21を使用します。Android Studio同梱のJDK 25はGradle 8.9と互換性がありません。サンドボックスでホームディレクトリが解決できない場合は、プロセスの `ANDROID_USER_HOME` と `GRADLE_USER_HOME` を明示してください。通常APKの更新互換性を維持するため、既存の署名鍵を使い、証明書を比較します。

## 2026-09-12 リファクタリング

計画した5点を、再描画 → メニュー → 保存 → 読み込み → 翻訳の順に実施しました。

- `ViewerActivity.refreshReader()`へ設定変更後の再描画を統一。余白変更でも縦連続スクロールを再描画し、キャッシュを無効化します。
- `Ui.Actions`に表示名とコールバックを一緒に登録。MainActivityとViewerActivityのメニューから翻訳文字列での分岐を除去しました。同名の項目も別の処理を実行できます。
- 同期のローカル保存はAppStateへ集約。保存キー・履歴上限の定義を維持し、インポート時にはリモートの更新時刻を保持します。
- `PageSource`が画像／ZIP／PDFの生画像デコードとファイルハンドルを所有。閲覧と一覧サムネイルから共用し、読書用の合成・切り抜き・フィルターはViewerActivity側に残しています。開始・デコード・終了は各呼び出し元のワーカーで直列に実行します。
- 翻訳401件のリソース名を英語の意味に基づく名前へ変更し、Java内のフォールバック文言を削除。XMLを文言の管理元に統一し、Application起動時にロケールを初期化します。翻訳済みの文面と部分翻訳のフォールバック方針は維持しています。

回帰テストには、実際の余白ダイアログからの変更・縦ページの画素幅、重複表示名のメニュー、同期後の位置／時刻／しおり維持、ZIP／PDFのデコード、closeの再実行とclose後の読み込み拒否、アーカイブの展開サイズ制限を追加しています。保存形式の移行や新しい依存ライブラリの追加はありません。

検証結果: Instrumentation 67項目、自然順ソート14項目が成功。Lintは0 errors / 28 warnings。設定と一覧の撮影結果を確認しました。通常版Debug APKの署名・権限・16KBアラインメント検査も成功しています。APK SHA-256: `36C6D7D0CF6BDD5EFCCAABC5556F838BEF34C39E5D926696364CF16D4E709FC1`。

## 追加機能の検証

既存インストールと読書データを保護するため、次のテストは別applicationIdで実行します。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PcomicExplorerValidation=true
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w jp.yaman.comicexplorer.validation.test/jp.yaman.comicexplorer.ParityInstrumentation
```

Instrumentationは対象IDが`.validation`で終わることを確認してから、その検証アプリの設定を初期化します。通常APKを作る際は`comicExplorerValidation`を指定しません。

ネットワーク依存はSMBJ、Apache Commons Net、Google Play services authです。依存ライブラリ同梱のLICENSE／NOTICEはAPKへマージします。追加権限はINTERNETとギャラリー用画像読み取りで、`build.ps1`の許可リストで確認します。

FTPSでは端末標準の証明書検証とホスト名確認を有効にしています。[FTPSClientの仕様](https://commons.apache.org/proper/commons-net/apidocs/org/apache/commons/net/ftp/FTPSClient.html#setTrustManager(javax.net.ssl.TrustManager))に従い、`setTrustManager(null)`で標準TrustManagerを使います。自己署名証明書を無条件に許可する設定はありません。

## Google Drive同期の設定

Google CloudプロジェクトでDrive APIとOAuth同意画面を設定し、使用するAPKのパッケージ名・署名証明書SHA-1に対応するAndroid OAuthクライアントを登録してください。検証APKと通常APKではパッケージ名が異なります。アプリ内の「ページ再開同期」から認証し、同じアプリ登録の2台で同一内容の作品を開いて位置を確認します。クライアントシークレットをAPKに埋め込みません。

実装は[AndroidのAuthorizationClient](https://developer.android.com/identity/authorization)と[Drive appDataFolder](https://developers.google.com/workspace/drive/api/guides/appdata)を使用します。アップロード内容はSHA-256、ページ、総ページ数、更新時刻です。作品本文・ファイル名・端末内URIは送信しません。競合は端末時刻によるため大きな時計ずれでは新旧判定が不正確になります。大きな履歴では作品の再ハッシュに時間がかかります。

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

## 2026-09-18 読書操作の追加検証

`ParityInstrumentation` に幅合わせ時のドラッグ、画像端の移動制限、誤ページ送り防止、6倍ダブルタップ、ページ単位の補正による見開き中央線の保持を追加。当時はダブルタップ／フィルターのダイアログを通常文字と1.5倍文字で撮影した。現在は撮影だけの自動巡回を廃止し、該当UI変更時の目視確認とする。OS全体の文字サイズ設定・TalkBack操作を自動検証するものではない。

実行ログ: `build/reader-parity-test.txt`、ビルド: `build/reader-parity-build.txt`。参考APKの内部画像処理アルゴリズムは特定できていないため、画素一致や操作速度一致を合否条件にはしていない。
