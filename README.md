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

Android SDK（platforms/android-35 と build-tools/36.0.0）および JDK 21 を用意したWindows環境で実行します。

```powershell
./build.ps1
```

出力先は `dist/comic-explorer.apk` です。初回だけ、ローカル署名用のデバッグキーストアをプロジェクト直下（Git管理外）に生成します。
