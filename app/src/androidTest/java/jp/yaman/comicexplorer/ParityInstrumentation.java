package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Run only against the isolated .validation application ID, never a user's installed library. */
public final class ParityInstrumentation extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            check(context.getPackageName().endsWith(".validation"),"Refusing to modify non-validation app");
            AppState.prefs(context).edit().clear().commit();
            Uri book=Uri.parse("content://test/book");
            AppState.addRecent(context,book,"book.cbz","CBZ"); AppState.updateReadingProgress(context,book,3,10);
            AppState.setBookmark(context,book,3,true,"book.cbz","CBZ"); AppState.setBookmarkMemo(context,book,3,"note");
            AppState.clearPositions(context);
            check(AppState.getPosition(context,book)==0 && AppState.recents(context).get(0).position==0,"Clear positions updates history");
            check(AppState.hasBookmark(context,book,3) && AppState.bookmarkMemo(context,book,3).equals("note"),"Position deletion preserves bookmarks");
            AppState.updateReadingProgress(context,book,2,10); AppState.clearAllBookmarks(context);
            check(AppState.getPosition(context,book)==2 && AppState.bookmarks(context,book).isEmpty(),"Bookmark deletion preserves positions");
            AppState.setBookmark(context,book,2,true,"book.cbz","CBZ"); AppState.setBookmarkMemo(context,book,2,"preserved");
            AppState.setFavorite(context,book,"book.cbz","CBZ",true);
            Uri renamed=Uri.parse("content://test/renamed"); AppState.relocate(context,book,renamed,"renamed.cbz");
            check(AppState.getPosition(context,renamed)==2 && AppState.hasBookmark(context,renamed,2),"Rename preserves reading position and bookmarks");
            check(AppState.bookmarkMemo(context,renamed,2).equals("preserved") && AppState.isFavorite(context,renamed),"Rename preserves notes and favorites");
            check(AppState.recents(context).get(0).uri.equals(renamed),"Rename updates history URI");
            AppState.relocate(context,renamed,book,"book.cbz");
            AppState.put(context,"theme",2); AppState.resetSettings(context);
            check(AppState.number(context,"theme",0)==0 && AppState.getPosition(context,book)==2,"Settings reset preserves reading data");
            int[] levels={0xff404040,0xff808080,0xffc0c0c0}; ImageProcessing.autoContrast(levels);
            check(levels[0]==0xff000000 && levels[2]==0xffffffff,"Contrast derives range from pixels");
            int[] uniform={0xff666666,0xff666666}; ImageProcessing.autoContrast(uniform);
            check(uniform[0]==0xff666666,"Uniform contrast does not divide by zero");
            check(ImageProcessing.color(0xffff0000,true,false,0)==0xff4c4c4c,"Grayscale luminance");
            check(ImageProcessing.color(0xff000000,false,true,0)==0xffffffff,"Color inversion");
            check(ImageProcessing.kernel(0,2)==1 && ImageProcessing.kernel(3,2)==0,"Lanczos support");
            String hash=String.join("",java.util.Collections.nCopies(64,"a"));
            JSONObject merged=new JSONObject().put(hash,new JSONObject().put("page",3).put("total",10).put("updated",20));
            ReadingSync.merge(merged,new JSONObject().put(hash,new JSONObject().put("page",1).put("total",10).put("updated",10)));
            check(merged.getJSONObject(hash).getInt("page")==3,"Older remote progress cannot overwrite newer progress");
            ReadingSync.merge(merged,new JSONObject().put(hash,new JSONObject().put("page",9).put("total",10).put("updated",30)));
            check(merged.getJSONObject(hash).getInt("page")==9,"Newer progress merges");
            ReadingSync.merge(merged,new JSONObject().put("bad",new JSONObject().put("page",-1)));
            check(!merged.has("bad"),"Reject malformed sync records");
            AppState.identifyForSync(context,AppState.recents(context).get(0),hash);
            long recentTime=AppState.recents(context).get(0).timestamp;
            AppState.applySyncedProgress(context,merged);
            check(AppState.getPosition(context,book)==9 && AppState.recents(context).get(0).position==9,"Sync updates both saved and recent positions");
            check(AppState.recents(context).get(0).timestamp==recentTime && AppState.syncRecords(context).getJSONObject(hash).getLong("updated")==30,"Import preserves timestamps instead of creating a local edit");
            check(AppState.hasBookmark(context,book,2) && AppState.bookmarkMemo(context,book,2).equals("preserved"),"Sync preserves bookmarks and notes");
            boolean rejected=false; try{NetworkStorage.validatePath("books/../private");}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Network paths cannot escape their root");
            File cache=BookCache.directory(context,"thumbs"), old=new File(cache,"old.jpg"), recent=new File(cache,"recent.jpg");
            try(FileOutputStream out=new FileOutputStream(old)){out.write(new byte[20]);} old.setLastModified(1000);
            try(FileOutputStream out=new FileOutputStream(recent)){out.write(new byte[20]);}
            BookCache.trim(cache,20,0); check(!old.exists() && recent.exists(),"LRU cache trimming");
            BookCache.clear(context,"thumbs"); check(!recent.exists(),"Cache deletion");
            AppState.put(context,"language","en"); I18n.configure(context);
            check(I18n.t(R.string.ui_cancel).equals("Cancel"),"English resources");
            AppState.put(context,"language","ja"); I18n.configure(context);
            check(I18n.t(R.string.ui_cancel).equals("キャンセル"),"Japanese resources");
            AppState.put(context,"theme",1); Ui.configure(context); check(Ui.light && Ui.DARK_BACKGROUND==0xfffafafa,"Light theme");
            AppState.put(context,"theme",2); Ui.configure(context); check(!Ui.light && Ui.DARK_BACKGROUND==0xff212121,"Dark theme");
            File fixtures=new File(context.getFilesDir(),"parity-fixtures"); check(fixtures.isDirectory() || fixtures.mkdirs(),"Fixture directory");
            checkReaderGestures(context);
            checkFtp(fixtures);
            checkParityAdditions(context, fixtures);
            Bitmap cropBitmap=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);
            runOnMainSync(() -> {
                CropView crop=new CropView(context,cropBitmap); crop.setEdge(0,.25f);crop.setEdge(2,.1f);
                check(crop.selection().left==.25f && crop.selection().width()>=.009f,"Accessible crop keeps a nonempty rectangle");
            }); cropBitmap.recycle();
            File zip=new File(fixtures,"sample.cbz");
            try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(zip))){
                for(int i=0;i<6;i++) {
                    Bitmap image=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(image); canvas.drawColor(0xffeeeeee);
                    Paint paint=new Paint(3); paint.setColor(i%2==0?0xff557799:0xff997755); canvas.drawRect(40,40,560,200,paint);
                    paint.setTextSize(70); canvas.drawText("PAGE "+(i+1),80,420,paint); canvas.drawRect(80,500,520,820,paint);
                    output.putNextEntry(new ZipEntry("chapter"+(i/3+1)+"/page"+(i+1)+".png")); image.compress(Bitmap.CompressFormat.PNG,100,output); output.closeEntry(); image.recycle();
                }
            }
            File pdf=new File(fixtures,"sample.pdf"); android.graphics.pdf.PdfDocument document=new android.graphics.pdf.PdfDocument();
            for(int i=0;i<2;i++){ android.graphics.pdf.PdfDocument.Page page=document.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(600,900,i+1).create()); Paint paint=new Paint();paint.setTextSize(60);page.getCanvas().drawText("PDF "+(i+1),80,200,paint);document.finishPage(page); }
            try(FileOutputStream output=new FileOutputStream(pdf)){document.writeTo(output);}document.close();
            PageSource source=new PageSource(context,Uri.fromFile(zip),zip.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2_000_000);
            check(source.pageCount()==6 && source.pageName(3).equals("chapter2/page4.png"),"Extracted source preserves natural page order and chapter names");
            Bitmap decoded=source.decode(0,1080);check(decoded.getWidth()==600 && decoded.getHeight()==900,"Extracted ZIP decoding");decoded.recycle();
            source.close();source.close();boolean closed=false;
            try{source.decode(0,1080);}catch(java.io.IOException expected){closed=true;}
            check(closed,"Closed source rejects further decoding; close is idempotent");
            try(PageSource pdfSource=new PageSource(context,Uri.fromFile(pdf),pdf.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2_000_000)) {
                decoded=pdfSource.decode(1,1080);check(pdfSource.pageCount()==2 && decoded!=null,"Extracted PDF decoding");decoded.recycle();
            }
            try(PageSource.BoundedInputStream bounded=new PageSource.BoundedInputStream(new java.io.ByteArrayInputStream(new byte[]{1,2,3}),2)) {
                check(bounded.read(new byte[2])==2,"Bounded archive stream reads within limit");
                boolean overflowRejected=false;try{bounded.read();}catch(java.io.IOException expected){overflowRejected=true;}check(overflowRejected,"Bounded archive stream rejects overflow");
            }
            AppState.prefs(context).edit().clear().commit(); AppState.put(context,"language","ja"); AppState.put(context,"theme",2);
            ViewerActivity viewer=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(Uri.fromFile(zip)).putExtra(ViewerActivity.EXTRA_TITLE,zip.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> (Boolean)field(viewer,"initialized"),"ZIP reader initialized");
            check((Integer)field(viewer,"totalPages")==6,"ZIP page count");
            await(() -> ((ZoomImageView)field(viewer,"imageView")).getDrawable()!=null,"ZIP first page decoded");
            runOnMainSync(() -> { AppState.setPageLayout(context,AppState.PAGE_DUAL); AppState.put(context,"filter_contrast",true); invoke(viewer,"refreshReader"); });
            await(() -> {
                android.graphics.drawable.Drawable drawable = ((ZoomImageView)field(viewer,"imageView")).getDrawable();
                if (!(drawable instanceof android.graphics.drawable.BitmapDrawable)) return false;
                Bitmap spread = ((android.graphics.drawable.BitmapDrawable)drawable).getBitmap();
                return spread.getWidth()>spread.getHeight() && spread.getPixel(0,0)==0xffffffff
                        && spread.getPixel(spread.getWidth()/2,0)==0xff424242;
            }, "Per-page contrast leaves the spread divider unchanged");
            runOnMainSync(() -> { AppState.setPageLayout(context,AppState.PAGE_SINGLE); AppState.put(context,"filter_contrast",false); invoke(viewer,"refreshReader"); });
            runOnMainSync(() -> invoke(viewer,"toggleChrome")); screenshot(context,"reader-horizontal.png");
            runOnMainSync(() -> browser(viewer,false,false)); screenshot(context,"page-drawer.png");
            check(clickText(I18n.t(R.string.ui_chapters)),"Drawer switches to chapters"); screenshot(context,"chapter-drawer.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            runOnMainSync(() -> browser(viewer,false,true)); screenshot(context,"page-strip.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            runOnMainSync(() -> ReaderOptions.zoom(viewer,() -> {})); screenshot(context,"double-tap-options.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            runOnMainSync(() -> ReaderOptions.filters(viewer,() -> {})); screenshot(context,"filter-options.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            android.content.res.Configuration normalType = new android.content.res.Configuration(viewer.getResources().getConfiguration());
            try {
                runOnMainSync(() -> {
                    android.content.res.Configuration largeType = new android.content.res.Configuration(normalType); largeType.fontScale=1.5f;
                    viewer.getResources().updateConfiguration(largeType,viewer.getResources().getDisplayMetrics());
                    ReaderOptions.zoom(viewer,() -> {});
                });
                screenshot(context,"double-tap-large-type.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                runOnMainSync(() -> ReaderOptions.filters(viewer,() -> {})); screenshot(context,"filter-large-type.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            } finally { runOnMainSync(() -> viewer.getResources().updateConfiguration(normalType,viewer.getResources().getDisplayMetrics())); }

            runOnMainSync(() -> ReaderOptions.color(viewer,"grid_color",() -> {})); screenshot(context,"color-palette.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            runOnMainSync(() -> { AppState.setReadingFlow(context,1); invoke(viewer,"refreshReader"); });
            await(() -> ((ContinuousReader)field(viewer,"continuous")).getChildCount()>0,"Continuous pages rendered");
            runOnMainSync(() -> ((ContinuousReader)field(viewer,"continuous")).setSelection(3));
            await(() -> (Integer)field(viewer,"page")==3,"Continuous scrolling updates position");
            screenshot(context,"reader-vertical.png");
            runOnMainSync(() -> invoke(viewer,"saveCurrentPageCover"));
            await(() -> AppState.coverFile(context,Uri.fromFile(zip)).isFile(),"Continuous page can be saved as cover");
            int continuousGeneration=(Integer)field(field(viewer,"continuous"),"generation");
            runOnMainSync(() -> invoke(viewer,"showCropDialog"));
            await(() -> clickText("5%"),"Select margin crop through the actual dialog");
            await(() -> (Integer)field(field(viewer,"continuous"),"generation")>continuousGeneration,"Margin dialog refreshes continuous reader");
            await(() -> {
                ContinuousReader list=(ContinuousReader)field(viewer,"continuous");
                if(list.getChildCount()==0)return false;
                android.graphics.drawable.Drawable drawable=((ZoomImageView)list.getChildAt(0)).getDrawable();
                return drawable instanceof android.graphics.drawable.BitmapDrawable && ((android.graphics.drawable.BitmapDrawable)drawable).getBitmap().getWidth()==540;
            },"Visible continuous page reflects cropped pixels");
            int generation=(Integer)field(viewer,"renderGeneration");
            runOnMainSync(() -> { AppState.put(context,"filter_contrast",true);AppState.put(context,"filter_gray",true);invoke(viewer,"refreshReader"); });
            check((Integer)field(viewer,"renderGeneration")>generation,"Filter change invalidates prefetched images");
            await(() -> ((ContinuousReader)field(viewer,"continuous")).getChildCount()>0,"Combined filters render");
            runOnMainSync(viewer::finish); waitForIdleSync();
            AppState.setReadingFlow(context,0);
            ViewerActivity pdfViewer=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(Uri.fromFile(pdf)).putExtra(ViewerActivity.EXTRA_TITLE,pdf.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> (Boolean)field(pdfViewer,"initialized"),"PDF reader initialized");
            check((Integer)field(pdfViewer,"totalPages")==2,"PDF page count");
            await(() -> ((ZoomImageView)field(pdfViewer,"imageView")).getDrawable()!=null,"PDF first page decoded");
            runOnMainSync(() -> pdfViewer.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            await(() -> pdfViewer.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,"Landscape reader");
            await(() -> ((ZoomImageView)field(pdfViewer,"imageView")).getDrawable()!=null,"Landscape image decoded");
            await(() -> ((android.view.View)field(pdfViewer,"loading")).getVisibility()==android.view.View.GONE,"Landscape rendering complete");
            Thread.sleep(600);
            screenshot(context,"reader-landscape.png");
            runOnMainSync(() -> invoke(pdfViewer,"showCropEditor"));
            await(() -> {android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null && !root.findAccessibilityNodeInfosByText("画像トリミング").isEmpty();},"Crop dialog visible");
            screenshot(context,"crop-landscape.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            runOnMainSync(() -> pdfViewer.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            await(() -> pdfViewer.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT,"Restore portrait");
            runOnMainSync(pdfViewer::finish);
            AppState.addRecent(context,Uri.fromFile(zip),"sample.cbz","CBZ");AppState.addRecent(context,Uri.fromFile(pdf),"sample.pdf","PDF");
            AppState.setGridView(context,true);AppState.setGridColumns(context,3);AppState.put(context,"list_type",2);
            MainActivity library=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {try {java.lang.reflect.Method mode=MainActivity.class.getDeclaredMethod("selectMode",int.class);mode.setAccessible(true);mode.invoke(library,2);}catch(Exception e){throw new RuntimeException(e);}});
            await(() -> ((android.widget.GridView)field(library,"gridView")).getChildCount()>0,"History grid displays books");
            screenshot(context,"library-grid.png");
            runOnMainSync(() -> invoke(library,"selectItems")); screenshot(context,"bulk-selection.png"); sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            Albums.rename(context,null,"サンプルアルバム");
            runOnMainSync(() -> {try {java.lang.reflect.Method mode=MainActivity.class.getDeclaredMethod("selectMode",int.class);mode.setAccessible(true);mode.invoke(library,5);}catch(Exception e){throw new RuntimeException(e);}});
            screenshot(context,"albums.png"); runOnMainSync(library::finish);waitForIdleSync();
            SettingsActivity settings=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.util.concurrent.atomic.AtomicInteger selected=new java.util.concurrent.atomic.AtomicInteger();
            SettingsActivity menuActivity=settings;
            runOnMainSync(() -> {
                Ui.Actions menu=new Ui.Actions();menu.add("same label",()->selected.set(1));menu.add("same label",()->selected.set(2));
                android.app.AlertDialog dialog=menu.show(menuActivity,"menu test");
                dialog.getListView().performItemClick(null,1,1);dialog.dismiss();
            });
            check(selected.get()==2,"Menu dispatch does not depend on translated labels");
            screenshot(context,"settings-dark.png");
            runOnMainSync(settings::finish); waitForIdleSync(); AppState.put(context,"theme",1);
            settings=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            screenshot(context,"settings-light.png"); runOnMainSync(settings::finish); waitForIdleSync();
            AppState.put(context,"language","en");
            settings=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            screenshot(context,"settings-english.png"); runOnMainSync(settings::finish); waitForIdleSync();
            for (String locale : new String[]{"ko","ru"}) {
                AppState.put(context,"language",locale);
                settings=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                screenshot(context,"settings-"+locale+".png"); runOnMainSync(settings::finish);waitForIdleSync();
            }
            AppState.put(context,"language","ja"); AppState.put(context,"theme",2);
            AppState.put(context,"sync_enabled",true);AppState.prefs(context).edit().putString("sync.records","{}").apply();
            AppState.clearReadingData(context);
            check(!AppState.enabled(context,"sync_enabled",false) && !AppState.prefs(context).contains("sync.records"),"Reading data deletion disables sync and removes local sync records");
            result.putString("stream","PASS: "+checks+" checks\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) { result.putString("stream","FAIL: "+error+"\n"+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result); }
    }
    private void checkReaderGestures(Context context) {
        runOnMainSync(() -> {
            ZoomImageView image = new ZoomImageView(context);
            image.layout(0, 0, 200, 200);
            Bitmap bitmap = Bitmap.createBitmap(100, 300, Bitmap.Config.ARGB_8888);
            image.setImageBitmap(bitmap); image.setFitMode(AppState.FIT_WIDTH);
            android.graphics.RectF bounds = new android.graphics.RectF(0, 0, 100, 300);
            image.getImageMatrix().mapRect(bounds);
            check(Math.abs(bounds.height()-600)<1, "Width fit retains tall page content");
            final int[] swipes = {0};
            image.setInteractionListener(new ZoomImageView.InteractionListener() {
                public void onTap(float x) { }
                public void onSwipe(int direction) { swipes[0]++; }
            });
            image.setVerticalPaging(true);
            long now = android.os.SystemClock.uptimeMillis();
            touch(image, now, now, 0, 100, 100);
            touch(image, now, now+30, 2, 100, 1000);
            touch(image, now, now+50, 1, 100, 1000);
            bounds.set(0,0,100,300); image.getImageMatrix().mapRect(bounds);
            check(Math.abs(bounds.top)<1 && Math.abs(bounds.left)<1, "Pan clamps at top and centers the fitted axis");
            check(swipes[0]==0, "Panning an oversized fitted page cannot turn the page");
            touch(image, now+100, now+100, 0, 100, 100);
            touch(image, now+100, now+140, 2, 100, -1000);
            touch(image, now+100, now+160, 1, 100, -1000);
            bounds.set(0,0,100,300); image.getImageMatrix().mapRect(bounds);
            check(Math.abs(bounds.bottom-200)<1, "Pan clamps at bottom without losing the image");
            image.setFitMode(AppState.FIT_SCREEN);
            bounds.set(0,0,100,300); image.getImageMatrix().mapRect(bounds);
            check(Math.abs(bounds.height()-200)<1 && bounds.left>0, "Screen fit restores centered image");
            image.setDoubleTapMode(AppState.DOUBLE_TAP_ZOOM); image.setDoubleTapScale(6f);
            touch(image,now+1000,now+1000,0,100,100); touch(image,now+1000,now+1020,1,100,100);
            touch(image,now+1080,now+1080,0,100,100); touch(image,now+1080,now+1100,1,100,100);
            bounds.set(0,0,100,300); image.getImageMatrix().mapRect(bounds);
            check(Math.abs(bounds.height()-1200)<2, "Double tap honors the configured six-times zoom");
            image.setFitMode(AppState.FIT_SCREEN);
            image.setImageDrawable(null); bitmap.recycle();
        });
    }
    private static void touch(ZoomImageView view, long down, long time, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down,time,action,x,y,0);
        view.onTouchEvent(event); event.recycle();
    }

    private void checkParityAdditions(Context context, File fixtures) throws Exception {
        File rar = new File(fixtures, "stored.cbr");
        try (java.io.InputStream in = getContext().getAssets().open("stored.rar"); FileOutputStream out = new FileOutputStream(rar)) { DocumentTransfer.copyAndHash(in, out, null); }
        try (PageSource source = new PageSource(context, Uri.fromFile(rar), rar.getName(), null, java.nio.charset.StandardCharsets.UTF_8, 2_000_000)) {
            check(source.pageCount() == 2 && source.pageName(0).equals("chapter/page2.png"), "RAR4 pages sorted naturally");
            Bitmap image = source.decode(1, 1080); check(image != null && image.getPixel(0,0) == 0xffff0000, "RAR4 image decoded"); image.recycle();
        }
        File seven = new File(fixtures, "pages.7z"); Bitmap image = Bitmap.createBitmap(30, 40, Bitmap.Config.ARGB_8888); image.eraseColor(0xff2468ab);
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream(); image.compress(Bitmap.CompressFormat.PNG,100,png); image.recycle();
        try (org.apache.commons.compress.archivers.sevenz.SevenZOutputFile output = new org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(seven)) {
            for (String name : new String[]{"chapter/page10.png", "chapter/page2.png"}) {
                org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry = new org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry(); entry.setName(name);
                output.putArchiveEntry(entry); output.write(png.toByteArray()); output.closeArchiveEntry();
            }
        }
        try (PageSource source = new PageSource(context,Uri.fromFile(seven),seven.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2_000_000)) {
            check(source.pageCount()==2 && source.pageName(0).equals("chapter/page2.png"),"7z page count and sorting");
            image=source.decode(1,1080); check(image.getWidth()==30 && image.getPixel(0,0)==0xff2468ab,"7z LZMA2 image decoded"); image.recycle();
            image=source.decode(0,1080); check(image.getHeight()==40,"7z reverse navigation"); image.recycle();
        }
        Uri book=Uri.parse("content://test/cropped");
        android.graphics.RectF crop=new android.graphics.RectF(.1f,.2f,.8f,.9f); AppState.setCrop(context,book,crop);
        check(AppState.crop(context,book).equals(crop),"Crop persists per book");
        Uri newBook=Uri.parse("content://test/cropped-renamed"); AppState.relocate(context,book,newBook,"renamed.cbz");
        check(AppState.crop(context,book)==null && AppState.crop(context,newBook).equals(crop),"Crop follows rename");
        boolean invalid=false;try{AppState.setCrop(context,newBook,new android.graphics.RectF(-1,0,1,1));}catch(IllegalArgumentException expected){invalid=true;}check(invalid,"Reject invalid saved crop");
        Uri album=Albums.rename(context,null,"Album"); LibraryEntry albumImage=new LibraryEntry(newBook,"page.png","image/png","画像",false,0,0);
        Albums.update(context,album,java.util.Arrays.asList(albumImage,albumImage),true);check(Albums.list(context,album).size()==1,"Album additions deduplicate images");
        Albums.rename(context,album,"Renamed album");check(Albums.list(context,null).get(0).name.equals("Renamed album"),"Album rename persists");
        Albums.delete(context,album);check(AppState.crop(context,newBook).equals(crop),"Deleting album preserves source reading data");
        String unique="run-"+System.nanoTime();
        getUiAutomation().adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS");
        Uri tree=android.provider.DocumentsContract.buildTreeDocumentUri("jp.yaman.comicexplorer.parity.documents","root");
        Uri root=android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,"root");
        Uri run=android.provider.DocumentsContract.createDocument(context.getContentResolver(),root,android.provider.DocumentsContract.Document.MIME_TYPE_DIR,unique);
        try {
            Uri sourceDir=android.provider.DocumentsContract.createDocument(context.getContentResolver(),run,android.provider.DocumentsContract.Document.MIME_TYPE_DIR,"source");
            Uri target=android.provider.DocumentsContract.createDocument(context.getContentResolver(),run,android.provider.DocumentsContract.Document.MIME_TYPE_DIR,"target");
            Uri page=android.provider.DocumentsContract.createDocument(context.getContentResolver(),sourceDir,"image/png","page.png");
            try(java.io.OutputStream out=context.getContentResolver().openOutputStream(page)){out.write(png.toByteArray());}
            Uri note=android.provider.DocumentsContract.createDocument(context.getContentResolver(),sourceDir,"text/plain","notes.txt");
            try(java.io.OutputStream out=context.getContentResolver().openOutputStream(note)){out.write(new byte[]{1,2,3});}
            check(LibraryDirectoryReader.read(context.getContentResolver(),sourceDir,sourceDir,false).size()==2,"Read actual document folder, not tree root");
            AppState.addRecent(context,page,"page.png","画像"); AppState.updateReadingProgress(context,page,2,8); AppState.setBookmark(context,page,2,true,"page.png","画像"); AppState.setCrop(context,page,crop);
            LibraryEntry source=new LibraryEntry(sourceDir,"source",android.provider.DocumentsContract.Document.MIME_TYPE_DIR,"",true,0,0);
            Uri copied=new DocumentTransfer(context).transfer(source,target,false,DocumentTransfer.KEEP_BOTH);
            check(LibraryDirectoryReader.read(context.getContentResolver(),copied,copied,false).size()==2,"Copy folder includes non-comic files");
            boolean self=false;try{new DocumentTransfer(context).transfer(source,sourceDir,true,0);}catch(java.io.IOException expected){self=true;}check(self,"Reject moving folder into itself");
            Uri skipped=new DocumentTransfer(context).transfer(source,target,false,DocumentTransfer.SKIP);check(skipped==null,"Skip conflicts without changing source");
            Uri moved=new DocumentTransfer(context).transfer(source,target,true,DocumentTransfer.REPLACE);
            LibraryEntry movedPage=null;for(LibraryEntry child:LibraryDirectoryReader.read(context.getContentResolver(),moved,moved,false))if(child.name.equals("page.png"))movedPage=child;
            check(movedPage!=null && AppState.hasBookmark(context,movedPage.uri,2) && AppState.getPosition(context,movedPage.uri)==2 && AppState.crop(context,movedPage.uri).equals(crop),"Folder move migrates descendant bookmarks, progress and crop");
            DocumentTransfer.rename(context,new LibraryEntry(moved,"source",android.provider.DocumentsContract.Document.MIME_TYPE_DIR,"",true,0,0),"renamed");
            LibraryEntry renamedDir=null;for(LibraryEntry child:LibraryDirectoryReader.read(context.getContentResolver(),target,target,false))if(child.name.equals("renamed"))renamedDir=child;
            LibraryEntry renamedPage=null;for(LibraryEntry child:LibraryDirectoryReader.read(context.getContentResolver(),renamedDir.uri,renamedDir.uri,false))if(child.name.equals("page.png"))renamedPage=child;
            check(renamedPage!=null && AppState.hasBookmark(context,renamedPage.uri,2),"Folder rename migrates descendant reading data");
            Uri otherTree=android.provider.DocumentsContract.buildTreeDocumentUri("jp.yaman.comicexplorer.parity.documents2","root");
            Uri otherTarget=android.provider.DocumentsContract.buildDocumentUriUsingTree(otherTree,android.provider.DocumentsContract.getDocumentId(target));
            Uri cross=new DocumentTransfer(context).transfer(renamedPage,otherTarget,false,0);
            check(cross.getAuthority().equals(otherTarget.getAuthority()),"Cross-provider copy verified");
            Uri fail=android.provider.DocumentsContract.createDocument(context.getContentResolver(),run,"image/png","fail.png");
            try(java.io.OutputStream out=context.getContentResolver().openOutputStream(fail)){out.write(png.toByteArray());}
            boolean failed=false;try{new DocumentTransfer(context).transfer(new LibraryEntry(fail,"fail.png","image/png","画像",false,0,0),target,true,0);}catch(Exception expected){failed=true;}
            try(java.io.InputStream in=context.getContentResolver().openInputStream(fail)){check(failed && in.read()==137,"Failed move retains source bytes");}
            boolean partial=false;for(LibraryEntry child:LibraryDirectoryReader.read(context.getContentResolver(),target,target,false))if(child.name.equals("fail.png"))partial=true;
            check(!partial,"Failed transfer removes partial destination");
        } finally {android.provider.DocumentsContract.deleteDocument(context.getContentResolver(),run); getUiAutomation().dropShellPermissionIdentity();}
    }

    private void checkFtp(File directory) throws Exception {
        try(java.net.ServerSocket control=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            control.setSoTimeout(10000);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<byte[]> uploaded=new java.util.concurrent.atomic.AtomicReference<>();
            Thread server=new Thread(() -> {
                java.net.ServerSocket data=null;
                try(java.net.Socket socket=control.accept()) {
                    socket.setSoTimeout(10000);
                    java.io.BufferedReader input=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
                    java.io.PrintWriter output=new java.io.PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(),java.nio.charset.StandardCharsets.UTF_8),true);
                    output.print("220 Test FTP\r\n");output.flush(); String line;
                    while((line=input.readLine())!=null) {
                        String command=line.split(" ",2)[0]; String reply="200 OK";
                        if(command.equals("USER")) reply="331 Password required";
                        else if(command.equals("PASS")) reply="230 Logged in";
                        else if(command.equals("SYST")) reply="215 UNIX Type: L8";
                        else if(command.equals("PASV")) {
                            if(data!=null)data.close(); data=new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"));data.setSoTimeout(10000);
                            reply="227 Entering Passive Mode (127,0,0,1,"+(data.getLocalPort()/256)+","+(data.getLocalPort()%256)+")";
                        } else if(command.equals("LIST") || command.equals("RETR")) {
                            output.print("150 Opening data\r\n");output.flush();
                            try(java.net.Socket transfer=data.accept()) {transfer.getOutputStream().write((command.equals("LIST")?"-rw-r--r-- 1 test test 7 Jan 01 2026 page.cbz\r\n":"fixture").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                            data.close();data=null;reply="226 Complete";
                        } else if(command.equals("STOR")) {
                            output.print("150 Opening data\r\n"); output.flush();
                            try(java.net.Socket transfer=data.accept();java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()) {
                                DocumentTransfer.copyAndHash(transfer.getInputStream(),bytes,null);uploaded.set(bytes.toByteArray());
                            }
                            data.close();data=null;reply="226 Complete";
                        } else if(command.equals("QUIT")) {output.print("221 Bye\r\n");output.flush();break;}
                        else if(command.equals("FEAT"))reply="500 Unsupported";
                        output.print(reply+"\r\n");output.flush();
                    }
                } catch(Throwable e) {failure.set(e);} finally {if(data!=null)try{data.close();}catch(Exception ignored){}}
            },"test-ftp");server.start();
            JSONObject host=new JSONObject().put("protocol",1).put("host","127.0.0.1").put("port",control.getLocalPort()).put("passive",true);
            try(NetworkStorage.Remote remote=new NetworkStorage.Remote(host)) {
                java.util.ArrayList<NetworkStorage.Entry> files=remote.list("");
                check(files.size()==1 && files.get(0).name.equals("page.cbz"),"FTP passive directory listing");
                File copy=new File(directory,"ftp-download");remote.download("page.cbz",copy);
                check(new String(java.nio.file.Files.readAllBytes(copy.toPath()),java.nio.charset.StandardCharsets.UTF_8).equals("fixture"),"FTP binary retrieval");
                remote.upload("uploaded.cbz",copy);check(java.util.Arrays.equals(uploaded.get(),java.nio.file.Files.readAllBytes(copy.toPath())),"FTP binary upload");
            }
            server.join(10000);check(!server.isAlive() && failure.get()==null,"FTP connection closes cleanly");
        }
    }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void browser(ViewerActivity viewer, boolean chapters, boolean strip) {
        try {java.lang.reflect.Method method=ViewerActivity.class.getDeclaredMethod("showPageBrowser",boolean.class,boolean.class);method.setAccessible(true);method.invoke(viewer,chapters,strip);}
        catch(Exception e){throw new RuntimeException(e);}
    }
    private boolean clickText(String text) {
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null)return false;
        for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))
            if(text.contentEquals(node.getText()==null?"":node.getText()) && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return true;
        return false;
    }
    private void screenshot(Context context,String name)throws Exception {
        waitForIdleSync(); Thread.sleep(300); Bitmap screenshot=getUiAutomation().takeScreenshot(); check(screenshot!=null,"Screenshot "+name);
        try(FileOutputStream output=new FileOutputStream(new File(context.getFilesDir(),"parity-fixtures/"+name))){screenshot.compress(Bitmap.CompressFormat.PNG,100,output);} screenshot.recycle();
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition,String message)throws Exception {for(int i=0;i<100;i++){if(condition.get()){checks++;return;}Thread.sleep(100);}throw new AssertionError(message);}
    private static Object field(Object instance,String name) {try{java.lang.reflect.Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}catch(Exception e){throw new RuntimeException(e);}}
    private static void invoke(Object instance,String name){try{java.lang.reflect.Method method=instance.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(instance);}catch(Exception e){throw new RuntimeException(e);}}
}
