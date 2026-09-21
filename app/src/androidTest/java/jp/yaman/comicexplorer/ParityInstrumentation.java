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
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Run only against the isolated .validation application ID, never a user's installed library. */
public final class ParityInstrumentation extends Instrumentation {
    private int checks;
    private String suite;
    private String screenshots;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        suite=arguments==null ? "all" : arguments.getString("suite","all");
        screenshots=arguments==null ? null : arguments.getString("screenshots");
        start();
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();
            if (!context.getPackageName().endsWith(".validation")) throw new AssertionError("Refusing to modify non-validation app");
            if (!java.util.Arrays.asList("all","data","network","formats","reader","themes").contains(suite))
                throw new IllegalArgumentException("Unknown suite: "+suite+" (all, data, network, formats, reader, themes)");
            File fixtures=new File(context.getFilesDir(),"parity-fixtures");
            if (!fixtures.isDirectory() && !fixtures.mkdirs()) throw new java.io.IOException("Fixture directory");
            for(String group:new String[]{"data","network","formats","reader","themes"}) {
                if (!suite.equals("all") && !suite.equals(group)) continue;
                AppState.prefs(context).edit().clear().commit();
                AppState.put(context,"language","ja"); I18n.configure(context);
                AppState.put(context,"theme",2); Ui.configure(context);
                int before=checks;
                switch(group) {
                    case "data": checkData(context); checkTransfers(context); break;
                    case "network": checkNetwork(); checkFtp(fixtures); break;
                    case "formats": checkFormats(context,fixtures); break;
                    case "reader": checkReader(context,fixtures); break;
                    case "themes": checkThemes(context); break;
                }
                result.putInt(group,checks-before);
            }
            result.putString("stream","PASS ["+suite+"]: "+checks+" checks\n"); finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            result.putString("stream","FAIL ["+suite+"]: "+error+"\n"+android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    private void checkData(Context context) throws Exception {
            byte[] payload=new byte[150000]; new java.util.Random(7).nextBytes(payload);
            java.io.ByteArrayOutputStream copied=new java.io.ByteArrayOutputStream();
            byte[] copyHash=DocumentTransfer.copyAndHash(new java.io.ByteArrayInputStream(payload),copied,null);
            check(java.util.Arrays.equals(payload,copied.toByteArray()) && java.util.Arrays.equals(copyHash,java.security.MessageDigest.getInstance("SHA-256").digest(payload)),"Shared copy preserves multi-buffer bytes and verification hash");
            boolean canceled=false;
            try { Thread.currentThread().interrupt(); StreamCopy.copy(new java.io.ByteArrayInputStream(payload),copied,null); }
            catch(java.io.IOException expected) { canceled=true; }
            finally { Thread.interrupted(); }
            check(canceled,"Shared copy rejects interrupted work");
            boolean noSpace=false;
            java.io.File full=new java.io.File("unused") {
                @Override public java.io.File getParentFile() { return new java.io.File("unused") { @Override public long getUsableSpace() { return 0; } }; }
            };
            try { StreamCopy.copy(new java.io.ByteArrayInputStream(payload),copied,full); }
            catch(java.io.IOException expected) { noSpace=true; }
            check(noSpace,"Shared copy retains disk-space reserve");
            Uri book=Uri.parse("content://test/book");
            check(AppState.key(book).equals("98315341b0207c5ee81a54f42667fa511c3658df49095599f262b74d6ce922ad"),"URI keys retain the existing SHA-256 storage identity");
            AppState.addRecent(context,book,"book.cbz","CBZ"); AppState.updateReadingProgress(context,book,3,10);
            AppState.setBookmark(context,book,3,true,"book.cbz","CBZ"); AppState.setBookmarkMemo(context,book,3,"note");
            AppState.clearPositions(context);
            check(AppState.getPosition(context,book)==0 && AppState.recents(context).get(0).position==0,"Clear positions updates history");
            check(AppState.hasBookmark(context,book,3) && AppState.bookmarkMemo(context,book,3).equals("note"),"Position deletion preserves bookmarks");
            AppState.updateReadingProgress(context,book,2,10); AppState.clearAllBookmarks(context);
            check(AppState.getPosition(context,book)==2 && AppState.bookmarks(context,book).isEmpty(),"Bookmark deletion preserves positions");
            AppState.setBookmark(context,book,2,true,"book.cbz","CBZ"); AppState.setBookmarkMemo(context,book,2,"preserved");
            Uri renamed=Uri.parse("content://test/renamed"); AppState.relocate(context,book,renamed,"renamed.cbz");
            check(AppState.getPosition(context,renamed)==2 && AppState.hasBookmark(context,renamed,2),"Rename preserves reading position and bookmarks");
            check(AppState.bookmarkMemo(context,renamed,2).equals("preserved"),"Rename preserves bookmark notes");
            check(AppState.recents(context).get(0).uri.equals(renamed),"Rename updates history URI");
            AppState.relocate(context,renamed,book,"book.cbz");
            Uri legacy=Uri.parse("content://test/legacy-bookmark");
            AppState.prefs(context).edit().putStringSet("library.favorites",java.util.Collections.singleton(legacy.toString()))
                    .putStringSet("bookmark."+AppState.key(legacy),java.util.Collections.singleton("1"))
                    .putString("favorite."+AppState.key(legacy)+".title","legacy.cbz").apply();
            check(AppState.bookmarkedItems(context).stream().anyMatch(item -> item.uri.equals(legacy) && item.title.equals("legacy.cbz")),"Retiring favorites preserves legacy bookmark discovery");
            Uri legacyMoved=Uri.parse("content://test/legacy-moved"); AppState.relocate(context,legacy,legacyMoved,"moved.cbz");
            check(AppState.bookmarkedItems(context).stream().anyMatch(item -> item.uri.equals(legacyMoved) && item.title.equals("moved.cbz")),"Legacy bookmarks remain discoverable after rename");
            AppState.clearBookmarks(context,legacyMoved);
            AppState.put(context,"theme",2); AppState.resetSettings(context);
            check(AppState.number(context,"theme",0)==0 && AppState.getPosition(context,book)==2,"Settings reset preserves reading data");
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
            File cache=BookCache.directory(context,"thumbs"), old=new File(cache,"old.jpg"), recent=new File(cache,"recent.jpg");
            try(FileOutputStream out=new FileOutputStream(old)){out.write(new byte[20]);} old.setLastModified(1000);
            try(FileOutputStream out=new FileOutputStream(recent)){out.write(new byte[20]);}
            BookCache.trim(cache,20,0); check(!old.exists() && recent.exists(),"LRU cache trimming");
            BookCache.clear(context,"thumbs"); check(!recent.exists(),"Cache deletion");
            AppState.put(context,"sync_enabled",true);AppState.prefs(context).edit().putString("sync.records","{}").apply();
            AppState.clearReadingData(context);
            check(!AppState.enabled(context,"sync_enabled",false) && !AppState.prefs(context).contains("sync.records"),"Reading data deletion disables sync and removes local sync records");
    }
    private void checkNetwork() {
            boolean rejected=false; try{NetworkStorage.validatePath("books/../private");}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Network paths cannot escape their root");
    }
    private void checkReader(Context context, File fixtures) throws Exception {
            // Connect before opening windows; standalone runs have no earlier UI suite to do this.
            getUiAutomation();
            waitForIdleSync();
            check(AppState.direction(context)==AppState.DIRECTION_RTL,"Unset reading direction defaults to manga right-to-left order");
            AppState.setDirection(context,AppState.DIRECTION_LTR);
            check(AppState.direction(context)==AppState.DIRECTION_LTR,"Explicit left-to-right preference is preserved");
            AppState.put(context,"filter_contrast",true);
            Bitmap tonal=Bitmap.createBitmap(new int[]{0xff404040,0xff808080,0xffc0c0c0},3,1,Bitmap.Config.ARGB_8888);
            Bitmap adjusted=ImageProcessing.apply(context,tonal,3,100);
            check(adjusted.getPixel(0,0)==0xff000000 && adjusted.getPixel(2,0)==0xffffffff,"YUV contrast expands the tonal range"); adjusted.recycle();tonal.recycle();
            tonal=Bitmap.createBitmap(new int[]{0xff666666,0xff666666},2,1,Bitmap.Config.ARGB_8888);
            adjusted=ImageProcessing.apply(context,tonal,2,100);check(adjusted.getPixel(0,0)==0xff000000,"Uniform contrast follows reference normalization");adjusted.recycle();tonal.recycle();
            AppState.put(context,"filter_contrast",false);
            AppState.put(context,"filter_gray",true);tonal=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);tonal.eraseColor(0xffff0000);
            adjusted=ImageProcessing.apply(context,tonal,1,100);check(adjusted.getPixel(0,0)==0xff4c4c4c,"OpenCV grayscale luminance");adjusted.recycle();tonal.recycle();AppState.put(context,"filter_gray",false);
            AppState.put(context,"filter_invert",true);tonal=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);tonal.eraseColor(0xff000000);tonal.setHasAlpha(false);
            adjusted=ImageProcessing.apply(context,tonal,1,100);check(adjusted.getPixel(0,0)==0xffffffff,"Opaque inversion stays visible");adjusted.recycle();
            tonal.setHasAlpha(true);tonal.eraseColor(0x80402010);adjusted=ImageProcessing.apply(context,tonal,1,100);
            int invertedAlpha=android.graphics.Color.alpha(adjusted.getPixel(0,0)),sdk=android.os.Build.VERSION.SDK_INT;
            check(invertedAlpha==((sdk==31 || sdk==32) ? 255 : 1),"Inversion follows reference alpha and Android 12 correction");adjusted.recycle();tonal.recycle();AppState.put(context,"filter_invert",false);
            AppState.put(context,"filter_upscale",true);
            tonal=Bitmap.createBitmap(new int[]{0xff555555,0xff555555},2,1,Bitmap.Config.ARGB_8888);
            adjusted=ImageProcessing.apply(context,tonal,6,100);
            check(adjusted.getWidth()==6 && adjusted.getPixel(3,1)==0xff555555,"Lanczos4 preserves constant colors");adjusted.recycle();tonal.recycle();
            AppState.put(context,"filter_upscale",false);
            check(!ViewerActivity.usesDualPageLayout(AppState.PAGE_AUTO,android.content.res.Configuration.ORIENTATION_PORTRAIT)
                    && ViewerActivity.usesDualPageLayout(AppState.PAGE_AUTO,android.content.res.Configuration.ORIENTATION_LANDSCAPE),"Automatic spread follows orientation");
            int[] cover=ViewerActivity.coverSize(2000,1000);
            check(cover[0]==320 && cover[1]==160,"Cover resize preserves aspect ratio");
            check(PageSource.bitmapSampleSize(4000,6000,2_000_000)==4,"Large images are sampled within the pixel budget");
            int[] tallPdf=PageSource.pdfBitmapSize(1,100_000,1080,2_000_000);
            check(tallPdf[0]<=8192 && tallPdf[1]<=8192 && (long)tallPdf[0]*tallPdf[1]<=2_000_000,"Extreme PDF dimensions stay within texture and pixel limits");
            checkReaderGestures(context);
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
            File loose=new File(fixtures,"decode-parity.png");
            Bitmap archiveBitmap=source.decode(0,1080);
            try(FileOutputStream output=new FileOutputStream(loose)) { archiveBitmap.compress(Bitmap.CompressFormat.PNG,100,output); }
            try(PageSource imageSource=new PageSource(context,Uri.fromFile(loose),loose.getName(),new ArrayList<>(java.util.Collections.singletonList(Uri.fromFile(loose))),java.nio.charset.StandardCharsets.UTF_8,2_000_000)) {
                Bitmap looseBitmap=imageSource.decode(0,1080);
                check(looseBitmap.sameAs(archiveBitmap),"URI and archive image paths decode identical pixels"); looseBitmap.recycle();
            }
            archiveBitmap.recycle();
            source.close();source.close();boolean closed=false;
            try{source.decode(0,1080);}catch(java.io.IOException expected){closed=true;}
            check(closed,"Closed source rejects further decoding; close is idempotent");
            try(PageSource.BoundedInputStream bounded=new PageSource.BoundedInputStream(new java.io.ByteArrayInputStream(new byte[]{1,2,3}),2)) {
                check(bounded.read(new byte[2])==2,"Bounded archive stream reads within limit");
                boolean overflowRejected=false;try{bounded.read();}catch(java.io.IOException expected){overflowRejected=true;}check(overflowRejected,"Bounded archive stream rejects overflow");
            }
            AppState.prefs(context).edit().clear().commit(); AppState.put(context,"language","ja"); AppState.put(context,"theme",2);
            AppState.put(context,"start_fullscreen",true); AppState.markReaderHintSeen(context);
            ViewerActivity viewer=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(Uri.fromFile(zip)).putExtra(ViewerActivity.EXTRA_TITLE,zip.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                AppState.put(context,"page_both_next",true);
                AppState.put(context,"page_reverse",true);
                invoke(viewer,"updatePageButtons");
                check(((android.view.View)field(viewer,"leftPageButton")).getContentDescription().equals(I18n.t(R.string.ui_next_page))
                        && ((android.view.View)field(viewer,"rightPageButton")).getContentDescription().equals(I18n.t(R.string.ui_next_page)),
                        "Both-next takes precedence over reverse like the reference");
                AppState.put(context,"page_both_next",false);
                AppState.put(context,"page_reverse",false);
                invoke(viewer,"updatePageButtons");
            });
            awaitReady(() -> (Boolean)field(viewer,"initialized"),"ZIP reader initialized");
            awaitReady(() -> ((ZoomImageView)field(viewer,"imageView")).getDrawable()!=null,"ZIP first page decoded");
            runOnMainSync(() -> {
                try {
                    java.lang.reflect.Method next=ViewerActivity.class.getDeclaredMethod("nextIndex",int.class,boolean.class);next.setAccessible(true);
                    check((Integer)next.invoke(viewer,0,true)==1 && (Integer)next.invoke(viewer,0,false)==-1 && (Integer)next.invoke(viewer,5,true)==-1,"Reader navigation respects both page boundaries");
                } catch(Exception error) { throw new RuntimeException(error); }
            });
            runOnMainSync(() -> { invoke(viewer,"forward"); invoke(viewer,"forward"); });
            awaitReady(() -> (Integer)field(viewer,"page")==2,"Reader moved before launcher re-entry");
            ActivityMonitor launcherMonitor=addMonitor(MainActivity.class.getName(),null,false);
            try {
                runOnMainSync(() -> viewer.startActivity(new Intent(context,MainActivity.class)
                        .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)));
                awaitReady(() -> launcherMonitor.getLastActivity()!=null,"Duplicate launcher activity created");
                await(() -> launcherMonitor.getLastActivity().isDestroyed() && viewer.hasWindowFocus(),
                        "Launcher re-entry reveals the existing reader");
                check(!viewer.isFinishing() && (Integer)field(viewer,"page")==2,
                        "Launcher re-entry preserves the open page");
            } finally { removeMonitor(launcherMonitor); }
            runOnMainSync(() -> { invoke(viewer,"back"); invoke(viewer,"back"); });
            runOnMainSync(() -> ReaderOptions.direction(viewer,() -> invoke(viewer,"refreshReader")));
            awaitReady(() -> {
                android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                return root!=null && !root.findAccessibilityNodeInfosByText(I18n.t(R.string.ui_up)).isEmpty()
                        && !root.findAccessibilityNodeInfosByText(I18n.t(R.string.ui_down)).isEmpty();
            },"Page swipe direction offers vertical choices");
            awaitReady(() -> clickText(I18n.t(R.string.ui_up)),"Set upward page swipe");
            awaitReady(() -> AppState.pageSwipeDirection(context)==AppState.PAGE_SWIPE_UP
                    && (Boolean)field(field(viewer,"imageView"),"verticalPaging"),"Upward page swipe updates the reader gesture");
            runOnMainSync(() -> viewer.onSwipe(AppState.PAGE_SWIPE_UP));
            awaitReady(() -> (Integer)field(viewer,"page")==1,"Upward page swipe advances the page");
            runOnMainSync(() -> viewer.onSwipe(AppState.PAGE_SWIPE_DOWN));
            awaitReady(() -> (Integer)field(viewer,"page")==0,"Opposite vertical swipe returns to the previous page");
            runOnMainSync(() -> { AppState.setPageSwipeDirection(context,AppState.PAGE_SWIPE_RIGHT); invoke(viewer,"refreshReader"); });
            runOnMainSync(() -> {
                PageButtonDialog draft=new PageButtonDialog(viewer,() -> {});
                android.view.View content=(android.view.View)field(draft,"content");
                android.widget.CheckBox both=content.findViewById(R.id.pop_pagebtn_plpl_chk);
                android.widget.CheckBox reverse=content.findViewById(R.id.pop_pagebtn_reverse_chk);
                android.widget.CheckBox fixed=content.findViewById(R.id.pop_pagebtn_fix_chk);
                reverse.setChecked(true); both.setChecked(true);
                check(!reverse.isEnabled() && !fixed.isEnabled() && reverse.isChecked(),"Both-next disables irrelevant controls without discarding values");
                both.setChecked(false);
                check(reverse.isEnabled() && fixed.isEnabled(),"Direction controls become available again");
                ((android.widget.SeekBar)content.findViewById(R.id.pop_pagebtn_alpha_seek)).setProgress(0);
                android.view.View area=(android.view.View)field(draft,"minus");
                check(area.getVisibility()==android.view.View.VISIBLE && area.getAlpha()==1f && area.getBackground()!=null,
                        "Transparent preview retains editing outline");
                android.widget.TextView[] icons=(android.widget.TextView[])field(draft,"previewIcons");
                check(icons[0].getVisibility()==android.view.View.VISIBLE && icons[1].getVisibility()==android.view.View.VISIBLE
                        && !icons[0].getText().toString().equals(icons[1].getText().toString()),"Preview keeps distinct action icons at zero opacity");
                int firstColor=((android.graphics.drawable.GradientDrawable)icons[0].getBackground()).getColor().getDefaultColor();
                int secondColor=((android.graphics.drawable.GradientDrawable)icons[1].getBackground()).getColor().getDefaultColor();
                String firstIcon=icons[0].getText().toString();
                reverse.setChecked(!reverse.isChecked());
                check(firstColor!=secondColor && !firstIcon.equals(icons[0].getText().toString())
                        && ((android.graphics.drawable.GradientDrawable)icons[0].getBackground()).getColor().getDefaultColor()==secondColor,
                        "Reversing buttons swaps their preview symbols and colors");
                ((android.widget.RadioGroup)content.findViewById(R.id.pop_pagebtn_rdgp_position1)).check(R.id.pop_pagebtn_rdo_position_horizontal_both);
                both.setChecked(true);
                boolean allNext=true;
                for(android.widget.TextView icon:icons)allNext &= icon.getVisibility()==android.view.View.VISIBLE && "+".contentEquals(icon.getText());
                check(allNext,"Both edges preview four forward icons when all-next is enabled");
                both.setChecked(false);

                ((android.widget.RadioGroup)content.findViewById(R.id.pop_pagebtn_rdgp_type)).check(R.id.pop_pagebtn_rdo_type2);
                check(((android.widget.TextView)content.findViewById(R.id.pop_pagebtn_thick_value)).getText().toString().contains("5%"),
                        "Split-edge size shows each edge share");
                ((android.widget.RadioGroup)content.findViewById(R.id.pop_pagebtn_rdgp_type)).check(R.id.pop_pagebtn_rdo_type1);
                check(fixed.getVisibility()==android.view.View.GONE && content.findViewById(R.id.pop_pagebtn_rdgp_position2).getVisibility()==android.view.View.VISIBLE,
                        "Vertical layout exposes only relevant controls");
                ((android.widget.SeekBar)content.findViewById(R.id.pop_pagebtn_thick_seek)).setProgress(0);
                check(((android.widget.TextView)content.findViewById(R.id.page_button_hint)).getText().toString().equals(I18n.t(R.string.pb_zero_size)),
                        "Zero size explains missing tap area");
                ((android.widget.CheckBox)content.findViewById(R.id.pop_pagebtn_use_chk)).setChecked(false);
                check(!both.isEnabled() && !content.findViewById(R.id.pop_pagebtn_thick_seek).isEnabled()
                        && ((android.widget.TextView)content.findViewById(R.id.page_button_summary)).getText().toString().equals(I18n.t(R.string.pb_disabled)),
                        "Disabled page buttons have explicit preview state");
                check(AppState.pageButtonOpacity(context)==100 && PageButtonDialog.sizePercent(context)==10,
                        "Preview-only edits do not leak into saved settings");
            });
            capturePageButtons(viewer,"dark-buttons");
            runOnMainSync(() -> ReaderOptions.pageButtons(viewer,() -> invoke(viewer,"updatePageButtons")));
            capture("buttons-type0");
            awaitReady(() -> clickText(I18n.t(R.string.pb_type1)),"Select reference Type1");
            check(AppState.number(context,"page_type",0)==0,"Draft type does not save before OK");
            awaitReady(() -> clickText(I18n.t(R.string.pb_right)),"Type1 exposes Left/Right");
            capture("buttons-type1");
            awaitReady(() -> clickText(I18n.t(R.string.ui_cancel)),"Cancel page button draft");
            waitForIdleSync();
            check(AppState.number(context,"page_type",0)==0,"Cancel preserves saved type");
            runOnMainSync(() -> ReaderOptions.pageButtons(viewer,() -> invoke(viewer,"updatePageButtons")));
            awaitReady(() -> clickText(I18n.t(R.string.pb_type2)),"Select reference Type2");
            capture("buttons-type2");
            awaitReady(() -> clickText(I18n.t(R.string.ui_save)),"Save reference Type2");
            awaitReady(() -> AppState.number(context,"page_type",0)==2,"Wait for committed Type2");
            waitForIdleSync();
            check(AppState.number(context,"page_type",0)==2
                    && ((android.view.View)field(viewer,"leftPageButton")).getLayoutParams().height==((android.view.View)field(viewer,"pageCanvas")).getHeight(),"Type2 spans both side edges");
            runOnMainSync(() -> ReaderOptions.pageButtons(viewer,() -> invoke(viewer,"updatePageButtons")));
            awaitReady(() -> clickText(I18n.t(R.string.pb_type3)),"Select reference Type3");
            capture("buttons-type3");
            awaitReady(() -> clickText(I18n.t(R.string.pb_reset)),"Restore reference defaults in draft");
            check(AppState.number(context,"page_type",0)==2,"Default does not persist until OK");
            awaitReady(() -> clickText(I18n.t(R.string.ui_save)),"Save reference defaults");
            awaitReady(() -> AppState.number(context,"page_type",-1)==0,"Wait for committed defaults");
            waitForIdleSync();
            check(AppState.number(context,"page_type",-1)==0 && PageButtonDialog.sizePercent(context)==10
                    && AppState.pageButtonOpacity(context)==100 && !AppState.enabled(context,"page_fixed",true)
                    && !AppState.enabled(context,"scroll_smooth",true) && AppState.number(context,"scroll_overlap",-1)==23,"Reference defaults persist together");
            android.widget.FrameLayout.LayoutParams[] buttonBounds=PageButtonDialog.layouts(1,false,false,false,10,1000,2000);
            check(buttonBounds[0].width==100 && buttonBounds[0].height==1000
                    && buttonBounds[0].gravity==(android.view.Gravity.RIGHT|android.view.Gravity.TOP),"Type1 splits the selected vertical edge");
            buttonBounds=PageButtonDialog.layouts(3,true,true,false,10,1000,2000);
            check(buttonBounds[0].width==1000 && buttonBounds[0].height==100,"Type3 splits size across top and bottom edges");
            check(PageButtonDialog.forward(true,0,false,false,true,true)
                    && !PageButtonDialog.forward(true,0,false,false,false,true)
                    && PageButtonDialog.forward(true,1,false,false,false,true),"Fixed ignores reading direction only for horizontal button types");
            buttonBounds=PageButtonDialog.layouts(0,true,true,true,10,1000,2000);
            check(buttonBounds.length==4 && buttonBounds[0].height==100 && buttonBounds[2].height==100
                    && buttonBounds[0].gravity==(android.view.Gravity.BOTTOM|android.view.Gravity.LEFT)
                    && buttonBounds[2].gravity==(android.view.Gravity.TOP|android.view.Gravity.LEFT),"Both horizontal edges split the total size across four regions");
            buttonBounds=PageButtonDialog.layouts(1,true,true,true,65,1000,2000);
            check(buttonBounds.length==4 && buttonBounds[0].width==325 && buttonBounds[2].width==325
                    && buttonBounds[2].gravity==(android.view.Gravity.RIGHT|android.view.Gravity.TOP),"Both vertical edges cannot overlap at maximum size");
            runOnMainSync(() -> {
                PageButtonDialog draft=new PageButtonDialog(viewer,() -> invoke(viewer,"updatePageButtons"));
                ((android.widget.RadioGroup)field(draft,"horizontal")).check(R.id.pop_pagebtn_rdo_position_horizontal_both);
                invoke(draft,"save");
                android.widget.TextView[] extra=(android.widget.TextView[])field(viewer,"extraPageButtons");
                check(AppState.enabled(context,"page_horizontal_both",false) && extra[0].getVisibility()==android.view.View.VISIBLE
                        && extra[1].getVisibility()==android.view.View.VISIBLE,"Saving both edges creates additional reader buttons");
                PageButtonDialog reopened=new PageButtonDialog(viewer,() -> {});
                check(((android.widget.RadioGroup)field(reopened,"horizontal")).getCheckedRadioButtonId()==R.id.pop_pagebtn_rdo_position_horizontal_both,
                        "Both-edge selection survives reopening settings");
                for(android.widget.TextView button:extra)if(I18n.t(R.string.ui_next_page).contentEquals(button.getContentDescription()))button.performClick();
            });
            await(() -> (Integer)field(viewer,"page")==1,"Extra forward button advances a page");
            runOnMainSync(() -> {
                for(android.widget.TextView button:(android.widget.TextView[])field(viewer,"extraPageButtons"))
                    if(I18n.t(R.string.ui_previous_page).contentEquals(button.getContentDescription()))button.performClick();
            });
            await(() -> (Integer)field(viewer,"page")==0,"Extra previous button returns a page");
            runOnMainSync(() -> {
                PageButtonDialog draft=new PageButtonDialog(viewer,() -> invoke(viewer,"updatePageButtons"));
                ((android.widget.RadioGroup)field(draft,"types")).check(R.id.pop_pagebtn_rdo_type1);
                ((android.widget.RadioGroup)field(draft,"vertical")).check(R.id.pop_pagebtn_rdo_position_vertical_both);
                invoke(draft,"save");
                android.widget.TextView[] extra=(android.widget.TextView[])field(viewer,"extraPageButtons");
                check(AppState.enabled(context,"page_vertical_both",false) && extra[0].getVisibility()==android.view.View.VISIBLE,
                        "Vertical both-edge layout reaches the reader");
                AppState.put(context,"page_type",2); invoke(viewer,"updatePageButtons");
                check(extra[0].getVisibility()==android.view.View.GONE && extra[1].getVisibility()==android.view.View.GONE,
                        "Switching to a two-region layout removes extra tap targets");
                ReaderOptions.pageButtons(viewer,() -> invoke(viewer,"updatePageButtons"));
            });
            awaitReady(() -> clickText(I18n.t(R.string.pb_reset)),"Reset both-edge draft");
            awaitReady(() -> clickText(I18n.t(R.string.ui_save)),"Save reset both-edge settings");
            waitForIdleSync();
            check(!AppState.enabled(context,"page_horizontal_both",true) && !AppState.enabled(context,"page_vertical_both",true),
                    "Reset clears both-edge settings on both axes");
            runOnMainSync(() -> {
                invoke(viewer,"toggleChrome");
                android.widget.LinearLayout toolbar=(android.widget.LinearLayout)field(viewer,"readerMenuRow");
                int[] labels={R.string.ui_page_thumbnails,R.string.ui_reading_direction,R.string.ui_page_layout,R.string.ui_image_filters};
                boolean primary=toolbar.getChildCount()==labels.length;
                for(int i=0;primary && i<labels.length;i++)primary=I18n.t(labels[i]).contentEquals(toolbar.getChildAt(i).getContentDescription());
                check(primary,"Reader toolbar exposes only page list, direction, layout and filters");
                AppState.put(context,"key."+android.view.KeyEvent.KEYCODE_F1,5);
                check(ReaderOptions.keyAction(viewer,android.view.KeyEvent.KEYCODE_F1)==0,"Legacy fullscreen key assignment becomes disabled");
                AppState.prefs(context).edit().remove("setting.key."+android.view.KeyEvent.KEYCODE_F1).apply();
            });
            capture("dark-reader");
            runOnMainSync(() -> invoke(viewer,"showPageList"));
            awaitReady(() -> ((android.widget.GridView)((android.app.AlertDialog)field(viewer,"pageListDialog")).findViewById(android.R.id.list)).getChildCount()>0,"Central page list populated");
            capture("dark-pages");
            Object closingAdapter=((android.widget.GridView)((android.app.AlertDialog)field(viewer,"pageListDialog")).findViewById(android.R.id.list)).getAdapter();
            runOnMainSync(() -> {
                android.app.AlertDialog dialog=(android.app.AlertDialog)field(viewer,"pageListDialog");
                android.widget.GridView grid=dialog.findViewById(android.R.id.list);
                check(grid.getCount()==6 && dialog.getWindow().getAttributes().gravity==android.view.Gravity.CENTER,"Page list opens directly in a centered dialog");
                dialog.dismiss();
            });
            await(() -> (Boolean)field(closingAdapter,"closed"),"Closing page list cancels thumbnail work");
            runOnMainSync(() -> invoke(viewer,"showReaderMenu"));
            capture("dark-menu");
            android.view.accessibility.AccessibilityNodeInfo readerMenu=getUiAutomation().getRootInActiveWindow();
            for(int removed:new int[]{R.string.ui_brightness,R.string.ui_double_tap_zoom,R.string.ui_crop_this_book,R.string.ui_book_actions,R.string.ui_settings}) {
                check(readerMenu.findAccessibilityNodeInfosByText(I18n.t(removed)).isEmpty(),"Reader menu omits "+I18n.t(removed));
            }
            awaitReady(() -> clickText(I18n.t(R.string.ui_auto_page_turn)),"Automatic paging is reachable from reader menu");
            awaitReady(() -> clickText(I18n.t(R.string.ui_every_15_seconds)),"Start automatic paging");
            check((Integer)field(viewer,"autoDelayMs")==15000,"Reader menu starts automatic paging");
            runOnMainSync(() -> invoke(viewer,"showReaderMenu"));
            awaitReady(() -> clickText(I18n.t(R.string.ui_stop_auto_page_turn)),"Stop automatic paging from reader menu");
            check((Integer)field(viewer,"autoDelayMs")==0,"Reader menu stops automatic paging");
            runOnMainSync(() -> invoke(viewer,"showReaderMenu"));
            awaitReady(() -> clickText(I18n.t(R.string.ui_bookmark_actions)),"Bookmarks are reachable from reader menu");
            awaitReady(() -> clickText(I18n.t(R.string.ui_bookmark_this_page)),"Add bookmark through relocated control");
            check(AppState.hasBookmark(context,Uri.fromFile(zip),0),"Relocated bookmark action marks current page");

            runOnMainSync(() -> invoke(viewer,"showReaderMenu"));
            awaitReady(() -> clickText(I18n.t(R.string.ui_scroll_mode)),"Scroll mode opens directly from reader menu");
            awaitReady(() -> clickText(I18n.t(R.string.ui_horizontal_swipe)),"Select horizontal scrolling without intermediate categories");
            check(AppState.readingFlow(context)==AppState.FLOW_HORIZONTAL,"Flat reader menu applies scroll mode");

            SettingsActivity readerSettings=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {
                AppState.setFitMode(context,AppState.FIT_WIDTH);
                AppState.put(context,"double_tap_scale",250);
                AppState.put(context,"keep_screen_on",false);
                AppState.put(context,"brightness",42);
                readerSettings.finish();
            });
            await(() -> (Integer)field(field(viewer,"imageView"),"fitMode")==AppState.FIT_WIDTH
                    && (Float)field(field(viewer,"imageView"),"doubleTapScale")==2.5f
                    && Math.abs(viewer.getWindow().getAttributes().screenBrightness-.42f)<.001f
                    && (viewer.getWindow().getAttributes().flags & android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)==0,
                    "Returning from settings reapplies reader preferences");
            if(android.os.Build.VERSION.SDK_INT>=30) check(viewer.getWindow().getDecorView().getRootWindowInsets().isVisible(android.view.WindowInsets.Type.statusBars() | android.view.WindowInsets.Type.navigationBars()),"System bars remain visible despite legacy fullscreen preference");
            runOnMainSync(() -> {
                AppState.setFitMode(context,AppState.FIT_SCREEN);
                AppState.put(context,"double_tap_scale",180);
                AppState.put(context,"keep_screen_on",true);
                AppState.put(context,"brightness",-1);
                invoke(viewer,"applyReaderPreferences");
            });
            runOnMainSync(() -> { AppState.setPageLayout(context,AppState.PAGE_DUAL); AppState.put(context,"filter_contrast",true); invoke(viewer,"refreshReader"); });
            await(() -> {
                android.graphics.drawable.Drawable drawable = ((ZoomImageView)field(viewer,"imageView")).getDrawable();
                if (!(drawable instanceof android.graphics.drawable.BitmapDrawable)) return false;
                Bitmap spread = ((android.graphics.drawable.BitmapDrawable)drawable).getBitmap();
                return spread.getWidth()>spread.getHeight() && spread.getPixel(0,0)==0xffffffff
                        && spread.getPixel(spread.getWidth()/2,0)==0xff424242;
            }, "Per-page contrast leaves the spread divider unchanged");
            runOnMainSync(() -> { AppState.setPageLayout(context,AppState.PAGE_SINGLE); AppState.put(context,"filter_contrast",false); invoke(viewer,"refreshReader"); });
            runOnMainSync(() -> {
                invoke(viewer,"showPageList");
                android.widget.GridView grid=((android.app.AlertDialog)field(viewer,"pageListDialog")).findViewById(android.R.id.list);
                grid.performItemClick(null,3,3);
            });
            await(() -> (Integer)field(viewer,"page")==3 && field(viewer,"pageListDialog")==null,"Selecting a thumbnail navigates and closes the central list");
            runOnMainSync(() -> invoke(viewer,"showPageList"));
            awaitReady(() -> ((android.app.AlertDialog)field(viewer,"pageListDialog")).getWindow().getDecorView().hasWindowFocus(),"Page list receives input focus");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            await(() -> field(viewer,"pageListDialog")==null && !viewer.isFinishing(),"Back closes page list without closing reader");
            runOnMainSync(() -> { AppState.setReadingFlow(context,1); invoke(viewer,"refreshReader"); });
            awaitReady(() -> ((ContinuousReader)field(viewer,"continuous")).getChildCount()>0,"Continuous pages rendered");
            runOnMainSync(() -> { AppState.setReadingFlow(context,AppState.FLOW_HORIZONTAL); invoke(viewer,"refreshReader"); });
            awaitReady(() -> !((Boolean)field(field(viewer,"imageView"),"verticalPaging"))
                    && ((android.view.View)field(viewer,"imageView")).getVisibility()==android.view.View.VISIBLE
                    && ((android.view.View)field(viewer,"continuous")).getVisibility()==android.view.View.GONE,
                    "Returning to horizontal swipe updates the visible reader and gesture direction");
            runOnMainSync(() -> { AppState.setReadingFlow(context,AppState.FLOW_VERTICAL); invoke(viewer,"refreshReader"); });
            awaitReady(() -> ((ContinuousReader)field(viewer,"continuous")).getChildCount()>0,"Continuous pages return after horizontal swipe check");
            runOnMainSync(() -> {
                AppState.setDirection(context,AppState.DIRECTION_LTR);
                ((android.widget.LinearLayout)field(viewer,"readerMenuRow")).getChildAt(1).performClick();
            });
            awaitReady(() -> clickText(I18n.t(R.string.ui_left)), "Change direction through the toolbar");
            check(AppState.readingFlow(context)==AppState.FLOW_VERTICAL && AppState.pageSwipeDirection(context)==AppState.PAGE_SWIPE_LEFT,
                    "Page swipe direction changes preserve vertical scrolling");
            awaitReady(() -> ((android.view.View)field(viewer,"loading")).getVisibility()==android.view.View.GONE, "Direction change finished rendering");
            waitForIdleSync();
            runOnMainSync(() -> ((ContinuousReader)field(viewer,"continuous")).setSelection(3));
            await(() -> (Integer)field(viewer,"page")==3,"Continuous scrolling updates position");
            check(ContinuousReader.edgeDirection(false,true,-100,20)==1
                    && ContinuousReader.edgeDirection(true,false,100,20)==-1
                    && ContinuousReader.edgeDirection(false,true,100,20)==0
                    && ContinuousReader.edgeDirection(false,true,-20,20)==0,
                    "Continuous reader only exits a book on an outward edge swipe");

            runOnMainSync(() -> {AppState.put(context,"crop_percent",5);invoke(viewer,"refreshReader");});
            await(() -> {
                ContinuousReader list=(ContinuousReader)field(viewer,"continuous");
                if(list.getChildCount()==0)return false;
                android.graphics.drawable.Drawable drawable=((ZoomImageView)list.getChildAt(0)).getDrawable();
                return drawable instanceof android.graphics.drawable.BitmapDrawable && ((android.graphics.drawable.BitmapDrawable)drawable).getBitmap().getWidth()==540;
            },"Existing saved crop still renders after removing its reader controls");
            runOnMainSync(() -> { AppState.put(context,"filter_contrast",true);AppState.put(context,"filter_gray",true);invoke(viewer,"refreshReader"); });
            await(() -> {
                ContinuousReader list=(ContinuousReader)field(viewer,"continuous");
                if(list.getChildCount()==0)return false;
                android.graphics.drawable.Drawable drawable=((ZoomImageView)list.getChildAt(0)).getDrawable();
                if(!(drawable instanceof android.graphics.drawable.BitmapDrawable))return false;
                Bitmap page=((android.graphics.drawable.BitmapDrawable)drawable).getBitmap();
                int pixel=page.getPixel(page.getWidth()/2,100);
                return android.graphics.Color.red(pixel)==android.graphics.Color.green(pixel)
                        && android.graphics.Color.green(pixel)==android.graphics.Color.blue(pixel);
            },"Filter change replaces the visible colored page with grayscale pixels");
            runOnMainSync(viewer::finish); waitForIdleSync();
            AppState.setReadingFlow(context,0); AppState.put(context,"theme",1);
            ViewerActivity pdfViewer=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(Uri.fromFile(pdf)).putExtra(ViewerActivity.EXTRA_TITLE,pdf.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitReady(() -> (Boolean)field(pdfViewer,"initialized"),"PDF reader initialized");
            awaitReady(() -> ((ZoomImageView)field(pdfViewer,"imageView")).getDrawable()!=null,"PDF first page decoded");
            runOnMainSync(() -> pdfViewer.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            awaitReady(() -> pdfViewer.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,"Landscape reader");
            awaitReady(() -> ((ZoomImageView)field(pdfViewer,"imageView")).getDrawable()!=null,"Landscape image decoded");
            awaitReady(() -> ((android.view.View)field(pdfViewer,"loading")).getVisibility()==android.view.View.GONE,"Landscape rendering complete");
            runOnMainSync(() -> invoke(pdfViewer,"toggleChrome"));
            capture("light-landscape-reader");
            capturePageButtons(pdfViewer,"light-landscape-buttons");
            if(screenshots!=null) {
                runOnMainSync(() -> invoke(pdfViewer,"showReaderMenu"));
                capture("light-landscape-menu");
                sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                waitForIdleSync();
            }
            runOnMainSync(() -> invoke(pdfViewer,"showPageList"));
            capture("light-landscape-pages");
            runOnMainSync(() -> ((android.app.AlertDialog)field(pdfViewer,"pageListDialog")).dismiss());


            runOnMainSync(() -> pdfViewer.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            awaitReady(() -> pdfViewer.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT,"Restore portrait");
            runOnMainSync(pdfViewer::finish);
            AppState.addRecent(context,Uri.fromFile(zip),"sample.cbz","CBZ");AppState.addRecent(context,Uri.fromFile(pdf),"sample.pdf","PDF");
            AppState.setGridView(context,true);AppState.put(context,"grid_columns",3);AppState.put(context,"list_type",2);
            AppState.put(context,"theme",1);
            MainActivity lightLibrary=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> {try{java.lang.reflect.Method mode=MainActivity.class.getDeclaredMethod("selectMode",int.class);mode.setAccessible(true);mode.invoke(lightLibrary,2);}catch(Exception e){throw new RuntimeException(e);}});
            awaitReady(() -> ((android.widget.GridView)field(lightLibrary,"gridView")).getChildCount()>0,"Light-theme grid has visible books");
            runOnMainSync(() -> invoke(lightLibrary,"showAppMenu"));
            awaitReady(() -> {
                android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                return root!=null && !root.findAccessibilityNodeInfosByText(I18n.t(R.string.ui_list_type)).isEmpty();
            },"Library menu shows list type");
            android.view.accessibility.AccessibilityNodeInfo libraryMenu=getUiAutomation().getRootInActiveWindow();
            check(!libraryMenu.findAccessibilityNodeInfosByText(I18n.t(R.string.ui_sort)).isEmpty(),
                    "Library menu moves view and sort controls");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); waitForIdleSync();
            android.view.View firstCell=((android.widget.GridView)field(lightLibrary,"gridView")).getChildAt(0);
            int labelColor=((android.widget.TextView)field(firstCell.getTag(),"name")).getCurrentTextColor();
            check(androidx.core.graphics.ColorUtils.calculateContrast(labelColor,AppState.number(context,"grid_color",Ui.BACKGROUND))>=4.5,"Grid filename contrast follows selected background");
            java.util.concurrent.atomic.AtomicInteger selected=new java.util.concurrent.atomic.AtomicInteger();

            runOnMainSync(() -> {
                Ui.Actions menu=new Ui.Actions();menu.add("same label",()->selected.set(1));menu.add("same label",()->selected.set(2));
                android.app.AlertDialog dialog=menu.show(lightLibrary,"menu test");
                dialog.getListView().performItemClick(null,1,1);dialog.dismiss();
            });
            check(selected.get()==2,"Menu dispatch does not depend on translated labels");
            checkLibraryThumbnails(lightLibrary,Uri.fromFile(zip));
            LibraryEntry actionItem=new LibraryEntry(Uri.fromFile(zip),"sample.cbz",null,"CBZ",false,0,0);
            AppState.setBookmark(context,actionItem.uri,1,true,actionItem.name,actionItem.kind);
            File coverFixture=AppState.coverFile(context,actionItem.uri);coverFixture.getParentFile().mkdirs();
            Bitmap coverFixtureBitmap=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);
            try(FileOutputStream output=new FileOutputStream(coverFixture)){coverFixtureBitmap.compress(Bitmap.CompressFormat.PNG,100,output);}
            coverFixtureBitmap.recycle();
            runOnMainSync(() -> {
                try { java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("showActions",LibraryEntry.class);method.setAccessible(true);method.invoke(lightLibrary,actionItem); }
                catch(Exception error) { throw new RuntimeException(error); }
            });
            waitForIdleSync();
            check(getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText("お気に入り").isEmpty(),"Book actions omit retired favorites");
            awaitReady(() -> clickText(I18n.t(R.string.ui_delete_all_bookmarks_2)),"Select conditional bookmark action");
            check(AppState.bookmarks(context,actionItem.uri).isEmpty() && AppState.hasCover(context,actionItem.uri),"Conditional library actions clear bookmarks without removing cover");
            LibraryEntry folderAction=new LibraryEntry(android.provider.DocumentsContract.buildDocumentUri(context.getPackageName()+".network","root"),"folder",android.provider.DocumentsContract.Document.MIME_TYPE_DIR,"",true,0,0);
            runOnMainSync(() -> {
                try { java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("showActions",LibraryEntry.class);method.setAccessible(true);method.invoke(lightLibrary,folderAction); }
                catch(Exception error) { throw new RuntimeException(error); }
            });
            waitForIdleSync();
            awaitReady(() -> { android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow(); return root!=null && !root.findAccessibilityNodeInfosByText("folder").isEmpty(); },"Folder action menu visible");
            android.view.accessibility.AccessibilityNodeInfo folderMenu=getUiAutomation().getRootInActiveWindow();
            check(!folderMenu.findAccessibilityNodeInfosByText(I18n.t(R.string.ui_rename)).isEmpty()
                    && !folderMenu.findAccessibilityNodeInfosByText(I18n.t(R.string.ui_copy)).isEmpty()
                    && folderMenu.findAccessibilityNodeInfosByText("ディレクトリに登録").isEmpty(),"Folder actions retain file operations without saved folders");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); waitForIdleSync();
            android.app.Instrumentation.ActivityMonitor restoredMonitor=addMonitor(MainActivity.class.getName(),null,false);
            runOnMainSync(() -> {
                try { java.lang.reflect.Field mode=MainActivity.class.getDeclaredField("mode");mode.setAccessible(true);mode.setInt(lightLibrary,7); }
                catch(Exception error) { throw new RuntimeException(error); }
                lightLibrary.recreate();
            });
            Activity restoredLibrary=waitForMonitorWithTimeout(restoredMonitor,10000); removeMonitor(restoredMonitor);
            check(restoredLibrary!=null && ((Integer)field(restoredLibrary,"mode"))==0,"Retired gallery state restores to the library");
            runOnMainSync(restoredLibrary::finish); waitForIdleSync();
            if(screenshots!=null) {
                ViewerActivity unavailable=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(Uri.fromFile(new File(fixtures,"missing.cbz"))).putExtra(ViewerActivity.EXTRA_TITLE,"missing.cbz").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                awaitReady(() -> ((android.view.View)field(unavailable,"errorPanel")).getVisibility()==android.view.View.VISIBLE,"Missing book error visible");
                capture("missing-book");runOnMainSync(unavailable::finish);
            }

            checkForcedSinglePage(context,fixtures);
    }
    private void checkForcedSinglePage(Context context,File fixtures) throws Exception {
        AppState.prefs(context).edit().clear().commit(); AppState.put(context,"language","ja"); AppState.put(context,"theme",2);
        AppState.markReaderHintSeen(context);
        File book=new File(fixtures,"split.cbz");
        try(ZipOutputStream output=new ZipOutputStream(new FileOutputStream(book))) {
            int[][] sizes={{300,500},{601,400},{300,300}};
            for(int i=0;i<sizes.length;i++) {
                Bitmap bitmap=Bitmap.createBitmap(sizes[i][0],sizes[i][1],Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(bitmap);canvas.drawColor(android.graphics.Color.RED);
                if(i==1){Paint paint=new Paint();paint.setColor(android.graphics.Color.BLUE);canvas.drawRect(300,0,601,400,paint);}
                output.putNextEntry(new ZipEntry(i+".png"));bitmap.compress(Bitmap.CompressFormat.PNG,100,output);output.closeEntry();bitmap.recycle();
            }
        }
        byte[] original=java.nio.file.Files.readAllBytes(book.toPath());
        Uri uri=Uri.fromFile(book);
        try(PageSource source=new PageSource(context,uri,"split.cbz",null,java.nio.charset.StandardCharsets.UTF_8,2_000_000)) {
            PageSequence sequence=new PageSequence(source,true);
            check(!sequence.complete() && sequence.display(source,0,0)==0 && sequence.count()==3,"Force single opens after only the starting page is checked");
            sequence.scanAll(source);
            check(sequence.count()==4 && sequence.original(2)==1 && sequence.display(2,0)==3,"Only landscape images create two display pages; square and portrait stay whole");
            Bitmap right=sequence.decode(source,1,1080,-1,true),left=sequence.decode(source,2,1080,-1,true);
            check(right.getWidth()==301 && left.getWidth()==300 && right.getPixel(0,0)==android.graphics.Color.BLUE && left.getPixel(0,0)==android.graphics.Color.RED,"RTL split keeps every column of odd-width images in right-left order");right.recycle();left.recycle();
            left=sequence.decode(source,1,1080,-1,false);check(left.getPixel(0,0)==android.graphics.Color.RED,"LTR split starts with the left half");left.recycle();
        }
        ViewerActivity viewer=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(uri).putExtra(ViewerActivity.EXTRA_TITLE,"split.cbz").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitReady(() -> (Boolean)field(viewer,"initialized"),"Mixed book opened");
        runOnMainSync(() -> invoke(viewer,"showPageLayoutDialog"));
        capture("force-single-choice");
        awaitReady(() -> clickText(I18n.t(R.string.ui_force_single_page)),"Select force single page");
        awaitReady(() -> (Boolean)field(viewer,"initialized") && (Integer)field(viewer,"totalPages")==4,"Force single builds display sequence");
        runOnMainSync(() -> invoke(viewer,"forward"));
        awaitReady(() -> AppState.getPosition(context,uri)==1 && ((ZoomImageView)field(viewer,"imageView")).getDrawable()!=null,"First half loaded");
        capture("force-single-right");
        runOnMainSync(() -> invoke(viewer,"forward"));
        awaitReady(() -> AppState.prefs(context).getString("position_half."+AppState.key(uri),"").equals("1:1"),"Second half persisted");
        check((Integer)field(viewer,"page")==2 && AppState.totalPages(context,uri)==3,"Display pages retain original book progress coordinates");
        capture("force-single-left");
        runOnMainSync(() -> {invoke(viewer,"toggleBookmark");viewer.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);});
        awaitReady(() -> viewer.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE,"Rotate force single");
        check(!ViewerActivity.usesDualPageLayout(AppState.PAGE_FORCE_SINGLE,android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                && AppState.hasBookmark(context,uri,1),"Force single remains split in landscape and bookmarks the original page");
        capture("force-single-landscape");
        runOnMainSync(() -> {viewer.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);});
        awaitReady(() -> viewer.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT,"Restore split portrait");
        runOnMainSync(viewer::finish);waitForIdleSync();
        ViewerActivity resumed=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(uri).putExtra(ViewerActivity.EXTRA_TITLE,"split.cbz").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitReady(() -> (Boolean)field(resumed,"initialized") && (Integer)field(resumed,"page")==2,"Resume the second half");
        runOnMainSync(() -> {AppState.setPageLayout(context,AppState.PAGE_SINGLE);invoke(resumed,"refreshReader");});
        awaitReady(() -> (Boolean)field(resumed,"initialized") && (Integer)field(resumed,"totalPages")==3,"Return to normal single page");
        check((Integer)field(resumed,"page")==1,"Leaving split mode preserves the original image");
        runOnMainSync(() -> {AppState.setPageLayout(context,AppState.PAGE_FORCE_SINGLE);AppState.setReadingFlow(context,AppState.FLOW_VERTICAL);invoke(resumed,"refreshReader");});
        awaitReady(() -> (Boolean)field(resumed,"initialized") && ((ContinuousReader)field(resumed,"continuous")).getCount()==4,"Continuous scrolling uses split display pages");
        capture("force-single-vertical");
        runOnMainSync(resumed::finish);waitForIdleSync();
        check(java.util.Arrays.equals(original,java.nio.file.Files.readAllBytes(book.toPath())),"Force single never changes archive bytes");
        AppState.clearPosition(context,uri);
        check(!AppState.prefs(context).contains("position_half."+AppState.key(uri)),"Reading position reset clears the saved half");
        AppState.setPageLayout(context,AppState.PAGE_SINGLE);
        AppState.setReadingFlow(context,AppState.FLOW_HORIZONTAL);
        AppState.prefs(context).edit().remove("page_swipe_direction").apply();
    }

    private void checkLibraryThumbnails(Activity activity,Uri uri) throws Exception {
        LibraryThumbnails loader=new LibraryThumbnails(activity);
        android.widget.ImageView view=new android.widget.ImageView(activity);
        android.widget.TextView badge=new android.widget.TextView(activity);
        LibraryEntry item=new LibraryEntry(uri,"sample.cbz",null,"CBZ",false,0,0);
        try {
            runOnMainSync(() -> loader.bind(view,badge,item));
            await(() -> view.getScaleType()==android.widget.ImageView.ScaleType.CENTER_CROP,"Custom cover loads through shared thumbnail pipeline");
            java.util.concurrent.ExecutorService worker=(java.util.concurrent.ExecutorService)field(loader,"worker");
            java.util.concurrent.CountDownLatch ready=new java.util.concurrent.CountDownLatch(2),release=new java.util.concurrent.CountDownLatch(1);
            for(int i=0;i<2;i++)worker.execute(() -> { ready.countDown();try {release.await();}catch(InterruptedException error){Thread.currentThread().interrupt();} });
            try {
                if(!ready.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Thumbnail workers ready");
                runOnMainSync(() -> {loader.clear();loader.bind(view,badge,item);loader.clear();});
            } finally {release.countDown();}
            worker.shutdown();if(!worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Thumbnail workers drained");
            waitForIdleSync();
            check(view.getScaleType()==android.widget.ImageView.ScaleType.CENTER_INSIDE && ((BitmapMemoryCache)field(loader,"cache")).size()==0,"Invalidated thumbnail work cannot restore an old cover or cache entry");
        } finally {runOnMainSync(loader::close);}
    }

    private void checkThemes(Context context) throws Exception {
        int original = AppState.number(context,"theme",0);
        AppState.put(context,"theme",1); Ui.configure(context);
        check(Ui.light,"Legacy light theme ID retains its meaning");
        AppState.put(context,"theme",2); Ui.configure(context);
        check(!Ui.light && Ui.themeStyle==R.style.AppTheme,"Legacy dark theme ID retains its meaning");
        java.util.Set<Integer> backgrounds=new java.util.HashSet<>();
        for (int theme=1;theme<Ui.THEMES.length;theme++) {
            AppState.put(context,"theme",theme); Ui.configure(context);
            boolean contrast = androidx.core.graphics.ColorUtils.calculateContrast(Ui.TEXT_PRIMARY,Ui.SURFACE)>=4.5
                    && androidx.core.graphics.ColorUtils.calculateContrast(Ui.TEXT_SECONDARY,Ui.BACKGROUND)>=4.5
                    && androidx.core.graphics.ColorUtils.calculateContrast(Ui.BRAND,Ui.SURFACE_RAISED)>=4.5
                    && androidx.core.graphics.ColorUtils.calculateContrast(Ui.ON_BRAND,Ui.BRAND)>=4.5
                    && androidx.core.graphics.ColorUtils.calculateContrast(Ui.TEXT_PRIMARY,Ui.TOOLBAR)>=4.5;
            check(backgrounds.add(Ui.BACKGROUND),"Each theme has a distinct base surface");
            check(Ui.light==(theme==1 || theme==7 || theme==8),"Light palettes use light UI behavior");
            android.util.TypedValue accent=new android.util.TypedValue();
            new android.view.ContextThemeWrapper(context,Ui.themeStyle).getTheme().resolveAttribute(android.R.attr.colorAccent,accent,true);
            check(contrast && accent.data==Ui.BRAND,"Theme "+theme+" has readable controls and matching native accent");
        }
        AppState.put(context,"theme",-1); Ui.configure(context);
        check(Ui.themeIndex(context)==0,"Unknown theme safely follows the system");
        AppState.put(context,"theme",original); Ui.configure(context);
        SettingsActivity settings=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ActivityMonitor monitor=addMonitor(SettingsActivity.class.getName(),null,false);
        try {
            runOnMainSync(() -> invoke(settings,"showThemePicker"));
            capture("theme-picker");
            awaitReady(() -> clickText(I18n.t(R.string.ui_theme_orange)),"Select sepia in the actual theme picker");
            Activity changed=waitForMonitorWithTimeout(monitor,10000);
            check(changed!=null && AppState.number(context,"theme",0)==8 && Ui.BRAND==Ui.themeAccent(context,8),
                    "Selecting a theme saves it and recreates settings with the selected palette");
            capture("theme-sepia");
            runOnMainSync(() -> invoke(changed,"showThemePicker"));
            awaitReady(() -> clickText(I18n.t(R.string.ui_cancel)),"Cancel theme picker");
            check(AppState.number(context,"theme",0)==8,"Cancelling the picker preserves the selected theme");
            runOnMainSync(changed::finish);
            if(screenshots!=null)for(int theme:new int[]{1,2,4}) {
                AppState.put(context,"theme",theme);
                SettingsActivity sample=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                capture("theme-"+theme);
                runOnMainSync(sample::finish);
            }
            AppState.put(context,"grid_color",0xff123456);
            SettingsActivity backgroundSettings=(SettingsActivity)startActivitySync(new Intent(context,SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            runOnMainSync(() -> ReaderOptions.color(backgroundSettings,"grid_color",() -> {}));
            awaitReady(() -> clickText(I18n.t(R.string.ui_match_theme)),"Restore theme-managed background");
            await(() -> !AppState.prefs(context).contains("setting.grid_color"),"Theme background reset removes only the explicit color override");
            runOnMainSync(backgroundSettings::finish);

        } finally { removeMonitor(monitor); AppState.put(context,"theme",original); Ui.configure(context); }
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
            ((android.animation.ValueAnimator)field(image,"zoomAnimator")).end();
            bounds.set(0,0,100,300); image.getImageMatrix().mapRect(bounds);
            check(Math.abs(bounds.height()-1000)<2, "Double tap clamps at the reference five-times maximum");
            image.setFitMode(AppState.FIT_SCREEN);
            image.setImageDrawable(null); bitmap.recycle();
        });
    }
    private static void touch(ZoomImageView view, long down, long time, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down,time,action,x,y,0);
        view.onTouchEvent(event); event.recycle();
    }
    private void checkFormats(Context context, File fixtures) throws Exception {
        checkAdvancedFormats(context,fixtures);
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
    }
    private void checkTransfers(Context context) throws Exception {
        Bitmap image=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
        java.io.ByteArrayOutputStream png=new java.io.ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG,100,png); image.recycle();
        Uri newBook=Uri.parse("content://test/cropped");
        android.graphics.RectF crop=new android.graphics.RectF(.1f,.2f,.8f,.9f);
        AppState.prefs(context).edit().putString("crop."+AppState.key(newBook),"[-1,0,1,1]").apply();
        check(AppState.crop(context,newBook)==null,"Reject out-of-bounds legacy crop on read");
        AppState.prefs(context).edit().putString("crop."+AppState.key(newBook),"invalid").apply();
        check(AppState.crop(context,newBook)==null,"Reject malformed legacy crop on read");
        String savedCrop="[0.1,0.2,0.8,0.9]";
        AppState.prefs(context).edit().putString("crop."+AppState.key(newBook),savedCrop).apply();
        check(AppState.crop(context,newBook).equals(crop),"Read valid legacy crop without the retired editor");
        String unique="run-"+System.nanoTime();
        getUiAutomation().adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS");
        String providerAuthority=getContext().getPackageName()+".parity.documents";
        Uri tree=android.provider.DocumentsContract.buildTreeDocumentUri(providerAuthority,"root");
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
            AppState.addRecent(context,page,"page.png","画像"); AppState.updateReadingProgress(context,page,2,8); AppState.setBookmark(context,page,2,true,"page.png","画像"); AppState.prefs(context).edit().putString("crop."+AppState.key(page),savedCrop).apply();
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
            Uri otherTree=android.provider.DocumentsContract.buildTreeDocumentUri(providerAuthority+"2","root");
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
    private void checkAdvancedFormats(Context context,File fixtures) throws Exception {
        org.apache.commons.compress.archivers.zip.ZipArchiveEntry deflated=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("page.png");deflated.setMethod(8);
        org.apache.commons.compress.archivers.zip.ZipArchiveEntry ppmd=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("page.png");ppmd.setMethod(98);
        org.apache.commons.compress.archivers.zip.ZipArchiveEntry lzma=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("page.png");lzma.setMethod(14);
        org.apache.commons.compress.archivers.zip.ZipArchiveEntry reducing=new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("page.png");reducing.setMethod(2);
        check(!PageSource.requiresSevenZip(deflated) && PageSource.requiresSevenZip(ppmd) && PageSource.requiresSevenZip(lzma)
                && PageSource.supportsSevenZipZipMethod(14) && !PageSource.supportsSevenZipZipMethod(reducing.getMethod()),"ZIP methods use the compatible reader");
        check(new PageSource.UnsupportedZipMethod(96).getMessage().endsWith("(96)"),"Unsupported ZIP method identifies its number");
        check(java.util.Arrays.equals(ViewerActivity.prefetchTargets(3,true,8,1),new int[]{4,5})
                && java.util.Arrays.equals(ViewerActivity.prefetchTargets(4,false,8,2),new int[]{2,0}),"Reader prefetches two pages in the reading direction");
        for(String name:new String[]{"stored-rar5.rar","encrypted.7z","split.7z.001","split.7z.002","split.part1.rar","split.part2.rar","animated.gif"}) {
            try(java.io.InputStream input=getContext().getAssets().open(name);FileOutputStream output=new FileOutputStream(new File(fixtures,name))){DocumentTransfer.copyAndHash(input,output,null);}
        }
        for(String name:new String[]{"stored-rar5.rar","split.7z.001","split.part1.rar"}) {
            try(PageSource source=new PageSource(context,Uri.fromFile(new File(fixtures,name)),name,null,java.nio.charset.StandardCharsets.UTF_8,2000000)) {
                Bitmap page=source.decode(0,100);check(source.pageCount()==1 && page.getPixel(0,0)==0xffff0000,"Decode "+name);page.recycle();
            }
        }
        File encrypted=new File(fixtures,"encrypted.7z");
        for(String password:new String[]{null,"wrong"}) {
            boolean rejected=false;try(PageSource source=new PageSource(context,Uri.fromFile(encrypted),encrypted.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2000000,password,java.util.Collections.emptyMap())){source.decode(0,100);}catch(ArchivePages.PasswordRequired expected){rejected=true;}
            check(rejected,"Encrypted archive requests correct password");
        }
        try(PageSource source=new PageSource(context,Uri.fromFile(encrypted),encrypted.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2000000,"parity",java.util.Collections.emptyMap())) {
            Bitmap page=source.decode(0,100);check(page.getPixel(0,0)==0xffff0000,"Decrypt 7z page");page.recycle();
        }
        Uri gif=Uri.fromFile(new File(fixtures,"animated.gif"));ArrayList<Uri> images=new ArrayList<>();images.add(gif);
        try(PageSource source=new PageSource(context,gif,"animated.gif",images,java.nio.charset.StandardCharsets.UTF_8,2000000)) {
            Bitmap first=source.decode(0,100,0),second=source.decode(0,100,110);
            check(first.getPixel(0,0)==0xffff0000 && second.getPixel(0,0)==0xff0000ff,"GIF decodes distinct timed frames");first.recycle();second.recycle();
        }
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);
        File outlined=new File(fixtures,"outlined.pdf");
        try(com.tom_roush.pdfbox.pdmodel.PDDocument document=new com.tom_roush.pdfbox.pdmodel.PDDocument()) {
            document.addPage(new com.tom_roush.pdfbox.pdmodel.PDPage());document.addPage(new com.tom_roush.pdfbox.pdmodel.PDPage());
            com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline outline=new com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem chapter=new com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem();
            chapter.setTitle("Chapter 2");chapter.setDestination(document.getPage(1));outline.addLast(chapter);document.save(outlined);
        }
        try(PageSource source=new PageSource(context,Uri.fromFile(outlined),outlined.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2000000)) {
            check(source.chapters.size()==1 && source.chapters.get(0).page==1 && source.chapters.get(0).title.equals("Chapter 2"),"PDF outline resolves page destinations");
        }
        File protectedPdf=new File(fixtures,"protected.pdf");
        try(com.tom_roush.pdfbox.pdmodel.PDDocument document=com.tom_roush.pdfbox.pdmodel.PDDocument.load(outlined)) {
            com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy protection=new com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","parity",new com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission());
            protection.setEncryptionKeyLength(128);protection.setPreferAES(true);document.protect(protection);document.save(protectedPdf);
        }
        boolean pdfPassword=false;
        try(PageSource source=new PageSource(context,Uri.fromFile(protectedPdf),protectedPdf.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2000000)){}catch(ArchivePages.PasswordRequired expected){pdfPassword=true;}
        check(pdfPassword,"Encrypted PDF requests password");
        try(PageSource source=new PageSource(context,Uri.fromFile(protectedPdf),protectedPdf.getName(),null,java.nio.charset.StandardCharsets.UTF_8,2000000,"parity",java.util.Collections.emptyMap())) {
            Bitmap page=source.decode(1,100);check(page!=null && source.chapters.get(0).page==1,"Encrypted PDF renders with outline");page.recycle();
        }
        ViewerActivity animated=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(gif).putExtra(ViewerActivity.EXTRA_TITLE,"animated.gif").putParcelableArrayListExtra(ViewerActivity.EXTRA_IMAGE_URIS,images).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitReady(() -> ((ZoomImageView)field(animated,"imageView")).getDrawable() instanceof AnimatedPageDrawable,"GIF reader starts animated drawable");
        await(() -> ((Bitmap)field(((ZoomImageView)field(animated,"imageView")).getDrawable(),"bitmap")).getPixel(0,0)==0xff0000ff,"GIF playback advances visible frame");
        int originalPage=(Integer)field(animated,"page");boolean chrome=(Boolean)field(animated,"chromeVisible");
        runOnMainSync(() -> animated.onTap(.05f));
        check((Integer)field(animated,"page")==originalPage && (Boolean)field(animated,"chromeVisible")!=chrome,"Page-edge tap toggles menus without turning pages");
        runOnMainSync(animated::finish);waitForIdleSync();
        ViewerActivity locked=(ViewerActivity)startActivitySync(new Intent(context,ViewerActivity.class).setData(Uri.fromFile(encrypted)).putExtra(ViewerActivity.EXTRA_TITLE,encrypted.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(() -> {android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null && !root.findAccessibilityNodeInfosByText("作品のパスワード").isEmpty();},"Password entry appears for encrypted book");

        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);runOnMainSync(locked::finish);waitForIdleSync();
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
    private void capture(String name) throws Exception {
        if(screenshots==null)return;
        if(!screenshots.matches("[a-z0-9-]{1,24}"))throw new IllegalArgumentException("Invalid screenshot prefix");
        waitForIdleSync();Thread.sleep(500);
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        if(bitmap==null)throw new java.io.IOException("Screenshot unavailable");
        try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),"reader-"+screenshots+"-"+name+".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,output);
        } finally {bitmap.recycle();}
    }
    private void capturePageButtons(Activity activity,String name) throws Exception {
        if(screenshots==null)return;
        PageButtonDialog[] draft=new PageButtonDialog[1];
        runOnMainSync(() -> {draft[0]=new PageButtonDialog(activity,() -> {});draft[0].show();});
        capture(name+"-layout");
        android.view.View content=(android.view.View)field(draft[0],"content");
        android.widget.ScrollView scroll=(android.widget.ScrollView)content.getParent();
        runOnMainSync(() -> scroll.scrollTo(0,content.findViewById(R.id.pop_pagebtn_layout_btn).getTop()));
        capture(name+"-preview");
        runOnMainSync(() -> ((android.widget.RadioGroup)content.findViewById(R.id.pop_pagebtn_rdgp_position1)).check(R.id.pop_pagebtn_rdo_position_horizontal_both));
        capture(name+"-both-horizontal");
        runOnMainSync(() -> {
            ((android.widget.RadioGroup)content.findViewById(R.id.pop_pagebtn_rdgp_type)).check(R.id.pop_pagebtn_rdo_type1);
            ((android.widget.RadioGroup)content.findViewById(R.id.pop_pagebtn_rdgp_position2)).check(R.id.pop_pagebtn_rdo_position_vertical_both);
        });
        capture(name+"-both-vertical");

        runOnMainSync(() -> {
            ((android.widget.SeekBar)content.findViewById(R.id.pop_pagebtn_alpha_seek)).setProgress(0);
            ((android.widget.CheckBox)content.findViewById(R.id.pop_pagebtn_plpl_chk)).setChecked(true);
        });
        capture(name+"-transparent");
        runOnMainSync(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
        capture(name+"-actions");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        waitForIdleSync();
    }
    private boolean clickText(String text) {
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null)return false;
        for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text))
            if(text.contentEquals(node.getText()==null?"":node.getText()) && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))return true;
        return false;
    }
    private interface Condition { boolean get() throws Exception; }
    private void await(Condition condition,String message)throws Exception {awaitReady(condition,message);checks++;}
    private void awaitReady(Condition condition,String message)throws Exception {for(int i=0;i<100;i++){if(condition.get())return;Thread.sleep(100);}throw new AssertionError(message);}
    private static Object field(Object instance,String name) {try{java.lang.reflect.Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);}catch(Exception e){throw new RuntimeException(e);}}
    private static void invoke(Object instance,String name){try{java.lang.reflect.Method method=instance.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(instance);}catch(Exception e){throw new RuntimeException(e);}}
}
