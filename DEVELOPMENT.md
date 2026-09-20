# 開発・検証・リリース手順

## 2026-09-20 ABI別Release APKと縮小

ReleaseはR8によるコード縮小とリソース縮小を有効にし、`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`を個別APKとして出力する。通常の配布先は`arm64-v8a`。`build.ps1 -Configuration Release -OutputDirectory dist`は4 APKそれぞれの署名・権限・ABIとSHA-256を検証し、`.sha256`を作成する。Debugは従来どおりuniversal APKを`dist/comic-explorer.apk`へ出力する。

実測はR8／resource shrink後の一時検証署名APK（配布・更新には使用不可）。基準のDebug universal APKは176,022,009 bytes。

| ABI | bytes | Debug比削減率 |
| --- | ---: | ---: |
| arm64-v8a（通常配布） | 31,731,018 | 81.41% |
| armeabi-v7a | 23,593,792 | 86.60% |
| x86 | 50,911,228 | 71.08% |
| x86_64 | 65,047,689 | 63.05% |

縮小Releaseはx86_64／Android 35・16 KiBエミュレーターで起動を確認。既存Instrumentationは別APKから元のクラス名を直接参照するため、R8後の対象APKでは最初の`AppState`参照で解決できず実行不可だった。全クラス保持で回避しないため、OpenCV画像処理、書庫展開、PDF処理、実ネットワーク接続は縮小Releaseでは未検証。Release用テストAPKを既存Debugテストと並行インストールできるよう、テストProvider authorityは`${applicationId}`基準にした。

## 2026-09-20 画像ビューの不要な操作を削除

読書メニューから明るさ、ダブルタップ動作、この本の切り抜き、この本の操作、アプリ設定を削除。上部の余白切り取りボタンも削除し、ページ一覧・送り方向・ページレイアウト・画像フィルターの4操作にした。入口を失った画像保存・表紙変更・切り抜き画面呼び出し等の専用処理をViewerActivityから除去。保存済みの表示設定、書庫側の設定入口、読書位置・しおり、強制単ページは維持する。

検証: 読書69チェック成功、Lint 0 errors / 85 warnings。ダーク縦画面のメニューとライト横画面のツールバー・メニューを目視確認。通常版 `dist/comic-explorer.apk` を更新し、署名・権限チェック成功。記録: `build/reader-prune-{build,reader,delivery}.txt`、`build/reader-prune-*.png`。


## 2026-09-20 強制単ページ

ページレイアウトに「強制単ページ」（保存ID 3）を追加。横長画像を中央で左右に分割し、左送りは右→左、右送りは左→右の順に表示する。縦長・正方形は分割せず、回転しても分割表示を維持。縦スクロール、ページ一覧、ジャンプも分割後のページ列を使う。元画像に対する本別切り抜きを適用してから分割し、余白処理と画像補正は片側ごとに適用する。

読書位置・しおり・章位置は元画像番号と対応付ける。片側の再開位置は別途保存し、通常表示へ戻しても元画像を維持する。しおりは元画像単位（開くと先に読む側へ移動）。元書庫・画像は変更しない。強制単ページで開く際に寸法を調べるため、大量の画像やRAR/7zでは初回表示までの処理が増える。PDFはページ寸法を使う。

検証: 読書70項目・データ40項目成功、Lint 0 errors / 63 warnings。混在CBZ、奇数幅の画素保持、左右順、設定選択、片側の再開、横画面、モード切り替え、縦スクロール、しおり、位置リセット、書庫バイト列の不変を確認。設定画面と左右・横画面・縦スクロールを撮影して目視確認した。実端末と大量書庫での速度は未検証。通常版 `dist/comic-explorer.apk` を更新、署名・権限検証成功。記録: `build/force-single-{build,reader,data,delivery}.txt`、`build/force-single-screens/`。


## 2026-09-20 ページボタン領域を参考アプリの仕様へ置換

指定の ComicScreen 2300 XAPK と既存解析APKのSHA-256一致を確認。`item_dialog_pagebtn`、4種類の領域レイアウト、設定処理を照合し、独自の配置名・説明・プレビュー構成を廃止。上段4チェック、Type0〜3と右側の見本、Type別位置選択、割合スライダー、スクロール設定、デフォルト／キャンセル／OKを再現した。変更はダイアログ内の下書きとし、OKでまとめて保存する。

Type0は上下いずれかの辺を左右半分、Type1は左右いずれかの辺を上下半分、Type2は左右全面、Type3は上下面。サイズは0〜65%（Type2/3は両辺に半分ずつ）。固定は送り方向による左右交換の抑止であり、メニュー表示中の常時表示ではない。+,+は反転より優先。既存の有効・不透明度等の値は保持し、旧dpサイズは新しい割合へ誤変換せず未設定時10%とする。

参考アプリはエミュレーターでGoogle Play関連の起動エラーが発生するため、同時実行による画素一致は未検証。APK内部のレイアウト・処理を実装根拠とする。

検証: 読書62チェック成功、Lint 0 errors / 63 warnings。明暗・Type0〜3・無効状態・横画面・文字150%を撮影確認。参考の固定幅レイアウトに従い、拡大文字ではラベルが折り返す。横画面の下部項目へはスクロールで到達する。端末の文字サイズ・回転設定は復元済み。通常版 `dist/comic-explorer.apk` を更新し、署名・権限チェック成功。記録: `build/reference-buttons-{final-build,reader,delivery}.txt`、`build/reference-buttons-screens/`、`build/reference-buttons-*.png`。


## 2026-09-20 ページボタンの配置プレビュー

Type0〜3を配置の説明へ変更し、追加ダイアログなしのラジオ選択にした。画面サンプルは選択中の種類・位置・有効状態・サイズ・透明度を反映する。実画面とサンプルは同じ配置計算を使用し、保存済みの数値IDは維持する。4言語の表示名を追加。日本語は最終的に「両端に配置」「端いっぱいに広げる」「左右の中央に固定」「中央にまとめる」、見出しは「ボタンの配置」「配置する辺」で確定し、リソースビルド成功（`build/button-labels-build.txt`）。

読書57チェック、文字150%でも57チェック、全体134チェック成功。その後、横画面のサンプルが選択肢を押し出すことを目視で検出し、回転に応じて高さを縮めた。最終ビルド・Lint成功（0 errors / 36 warnings）、ライト縦横とダークの各タイプ、拡大文字を目視確認。文字サイズ・画面回転設定は復元済み。実機・TalkBackは未検証。記録は `build/button-preview-{reader,large,all}.txt`、`build/button-preview-adaptive-build.txt`、`build/button-preview-*.png`。

## 2026-09-20 読書設定の階層削減

「表示・読み方」「読み方」「余白設定」「操作設定」の中間メニューを削除。スクロール方式、表示方法、画面回転、この本の切り抜き、ダブルタップ、ページボタン、ハードウェアキー、アプリ設定を読書メニューから直接開く。縦スクロール時はページ間隔も直接表示する。上部5ボタンと既存の保存値・確認操作は維持。不要な5文言は全4言語から削除した。

全体131チェック成功、最終ビルドとLint成功（0 errors / 36 warnings）。最終APKでは文字150%の読書54チェックも成功し、文字設定は100%へ復元。通常／拡大文字のダーク縦画面とライト横画面のメニューを目視確認。実機・TalkBackは未検証。記録は `build/flat-menu-all.txt`、`build/flat-menu-large.txt`、`build/flat-menu-final-build.txt`、`build/flat-menu-*.png`。

## 2026-09-20 読書ボタンの整理

上部ボタンをページ一覧・送り方向・ページレイアウト・画像フィルター・余白トリミングへ変更。しおり・明るさ・自動送りは読書メニューへ移動し、前後ページの矢印は維持した。ページ一覧は中央のグリッドダイアログに一本化し、ドロワーと帯状表示を削除。章一覧は「ページを探す」内の文字一覧として保持した。

全画面切替・起動時の全画面設定・キー割り当てを廃止。既存の全画面設定値は使わず、旧キー割り当てID 5は無効として扱う。方向・位置・しおり等の保存値は保持する。

検証用APKのビルド成功、Lintは0 errors / 36 warnings。API 37エミュレーターで全体130チェック、読書単独53チェック、文字サイズ150%でも読書53チェック成功。ライト／ダーク、縦／横、文字拡大、ファイル不在時の表示を撮影して目視確認した。文字サイズは検証後100%へ復元。実機・TalkBackによる読み上げ確認は未実施。ログは `build/reader-controls-{build,reader,large,all}.txt`、画像は `build/reader-controls-screens/`。通常版・配布用APKは未更新。

画面確認用にInstrumentationへ任意の `-e screenshots normal` を追加した。保存先は検証アプリの外部filesディレクトリ内の `reader-normal-*.png`。撮影はテスト成功件数に含めない。

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

Debugの出力先は既定で `dist/comic-explorer.apk` です。`-OutputPath`で変更できます。Debugは全ABIを含むuniversal APKなので、従来の開発・テスト導線を維持します。初回だけローカル署名用のデバッグキーストアをプロジェクト直下（Git管理外）に生成します。

Releaseは固定署名鍵を設定してから、次で作成します。

```powershell
./build.ps1 -Configuration Release -OutputDirectory dist
```

出力は`dist/comic-explorer-arm64-v8a.apk`（通常配布）、`dist/comic-explorer-armeabi-v7a.apk`、`dist/comic-explorer-x86.apk`、`dist/comic-explorer-x86_64.apk`と、それぞれの`.sha256`です。各APKについて署名、許可権限、ABI、SHA-256を検証します。Release用にuniversal APKは配布しません。

Releaseビルドは固定された署名鍵を必要とし、次の環境変数が不足している場合はデバッグ鍵へフォールバックせず失敗します。

- `ANDROID_KEYSTORE_PATH`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

鍵がない場合、Releaseはデバッグ鍵へフォールバックせず失敗します。ビルド後は各配布APKの署名、不要なAndroid権限、ABI、SHA-256を自動検証します。

### 手動確認の最小項目

- Android 10と、現在サポートする新しいAndroid版で起動できる
- SAFで選択した範囲だけを参照し、許可の再起動後保持と失効時表示が正しい
- JPG、PNG、GIF、BMP、WebP、PDF、ZIP / CBZを開ける
- AVIF対応端末ではAVIFを開け、非対応端末では復帰可能なエラーになる
- ページ位置、しおり、お気に入り、最近開いた作品が再起動後も復元される
- TalkBack、文字拡大、縦横画面、明暗の異なる表示条件で主要操作へ到達できる

## CIと自動Release

GitHub Actionsは用途を分離しています。

- `main`へのpushとPull Request: Debug universal APKをビルド・検証し、コミットSHAを含むWorkflow Artifactとして14日間保存します。正式Releaseは作成しません。
- `v*`タグのpush: 固定鍵で署名した4 ABI APKと各SHA-256ファイルを新しい正式Releaseへ添付します。`arm64-v8a`を通常配布として表示します。

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
