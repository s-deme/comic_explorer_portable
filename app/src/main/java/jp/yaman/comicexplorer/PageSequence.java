package jp.yaman.comicexplorer;

import android.graphics.Bitmap;
import java.io.IOException;
import java.util.Arrays;

/** Display pages mapped to original file pages, keeping bookmarks independent of layout. */
final class PageSequence {
    final boolean split;
    private final int[] starts;

    PageSequence(PageSource source, boolean split) throws IOException {
        this.split = split;
        starts = new int[source.pageCount() + 1];
        // ponytail: scan image headers once on open; persist bounds only if large archives need it.
        for (int i=0;i<source.pageCount();i++) starts[i+1]=starts[i]+(split && source.isLandscape(i) ? 2 : 1);
    }

    int count() { return starts[starts.length-1]; }
    int original(int page) {
        int index=Arrays.binarySearch(starts, Math.max(0, Math.min(count()-1,page)));
        return index>=0 ? index : -index-2;
    }
    int half(int page) { return page-starts[original(page)]; }
    int display(int original,int half) {
        int index=Math.max(0,Math.min(starts.length-2,original));
        return starts[index]+Math.max(0,Math.min(starts[index+1]-starts[index]-1,half));
    }
    Bitmap decode(PageSource source,int page,int width,long time,boolean rtl) throws IOException {
        int original=original(page);
        Bitmap bitmap=source.decode(original,split ? width*2 : width,time);
        return crop(bitmap,page,rtl);
    }
    Bitmap crop(Bitmap bitmap,int page,boolean rtl) {
        int original=original(page);
        return starts[original+1]-starts[original]==2 ? cropHalf(bitmap,half(page),rtl) : bitmap;
    }
    static Bitmap cropHalf(Bitmap bitmap,int half,boolean rtl) {
        if(bitmap.getWidth()<2)return bitmap;
        boolean right=(half==0)==rtl;
        int middle=bitmap.getWidth()/2;
        Bitmap cropped=Bitmap.createBitmap(bitmap,right ? middle : 0,0,right ? bitmap.getWidth()-middle : middle,bitmap.getHeight());
        if(cropped!=bitmap)bitmap.recycle();
        return cropped;
    }
}
