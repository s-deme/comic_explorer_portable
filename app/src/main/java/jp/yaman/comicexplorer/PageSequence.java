package jp.yaman.comicexplorer;

import android.graphics.Bitmap;
import java.io.IOException;
/** Display pages mapped to original file pages, keeping bookmarks independent of layout. */
final class PageSequence {
    final boolean split;
    private final int[] starts;
    private volatile int scanned;

    PageSequence(PageSource source, boolean split) {
        this.split = split;
        starts = new int[source.pageCount() + 1];
        if(!split) { for(int i=0;i<starts.length;i++)starts[i]=i;scanned=source.pageCount(); }
    }

    int count() { int known=scanned;return starts[known]+starts.length-1-known; }
    boolean complete() { return scanned==starts.length-1; }
    boolean scanNext(PageSource source) throws IOException {
        int index=scanned;if(index>=starts.length-1)return false;
        starts[index+1]=starts[index]+(source.isLandscape(index) ? 2 : 1);scanned=index+1;return true;
    }
    void scanAll(PageSource source) throws IOException { while(scanNext(source)) { } }
    int original(int page) {
        int known=scanned,target=Math.max(0,Math.min(count()-1,page));
        if(target>=starts[known])return Math.min(starts.length-2,known+target-starts[known]);
        int low=0,high=known;
        while(low<=high) { int middle=(low+high)>>>1;if(starts[middle]<=target)low=middle+1;else high=middle-1; }
        return Math.max(0,high);
    }
    int original(PageSource source,int page) throws IOException {
        while(page>=starts[scanned] && !complete())scanNext(source);
        return original(page);
    }
    int half(int page) { int known=scanned,target=Math.max(0,Math.min(count()-1,page));return target>=starts[known] ? 0 : target-starts[original(target)]; }
    int display(int original,int half) {
        int index=Math.max(0,Math.min(starts.length-2,original));
        if(index>=scanned)return starts[scanned]+index-scanned;
        return starts[index]+Math.max(0,Math.min(starts[index+1]-starts[index]-1,half));
    }
    int display(PageSource source,int original,int half) throws IOException { int index=Math.max(0,Math.min(starts.length-2,original));while(scanned<=index)scanNext(source);return display(index,half); }
    Bitmap decode(PageSource source,int page,int width,long time,boolean rtl) throws IOException {
        int original=original(source,page);
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
