package jp.yaman.comicexplorer;

import android.content.Context;
import android.graphics.Bitmap;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/** OpenCV operations run only on the reader worker. See the compatibility notes for limits. */
public final class ImageProcessing {
    private static final class Runtime { static final boolean READY = OpenCVLoader.initLocal(); }
    private ImageProcessing() { }
    public static Bitmap apply(Context context, Bitmap source, int targetWidth, int maxPixels) {
        int legacy=AppState.imageFilter(context);
        boolean gray=AppState.enabled(context,"filter_gray",legacy==1), contrast=AppState.enabled(context,"filter_contrast",legacy==2);
        boolean sharp=AppState.enabled(context,"filter_sharp",false), upscale=AppState.enabled(context,"filter_upscale",false);
        boolean invert=AppState.enabled(context,"filter_invert",false), blue=AppState.enabled(context,"filter_blue",legacy==4);
        if(!gray && !contrast && !sharp && !upscale && !invert && !blue)return source;
        if(!Runtime.READY)throw new IllegalStateException("OpenCV initialization failed");
        Mat image=new Mat(), temp=new Mat();
        try {
            Utils.bitmapToMat(source,image);
            if(gray) {Imgproc.cvtColor(image,temp,Imgproc.COLOR_RGBA2GRAY);Imgproc.cvtColor(temp,image,Imgproc.COLOR_GRAY2RGBA);}
            if(contrast) contrast(image);
            if(upscale && targetWidth>source.getWidth()) {
                double scale=Math.min(targetWidth/(double)source.getWidth(),Math.sqrt(maxPixels/(double)((long)source.getWidth()*source.getHeight())));
                int width=Math.max(1,(int)(source.getWidth()*scale)), height=Math.max(1,(int)(source.getHeight()*scale));
                int mode=AppState.number(context,"filter_interpolation",2);
                if(scale>1.01 && width<=5120 && height<=5120) Imgproc.resize(image,image,new Size(width,height),0,0,mode==0 ? Imgproc.INTER_LINEAR : mode==1 ? Imgproc.INTER_CUBIC : Imgproc.INTER_LANCZOS4);
            }
            int strength=Math.max(0,Math.min(20,AppState.number(context,"sharp_strength",0)));
            if(sharp && strength>0) {
                double amount=.4+strength/10.0;
                Imgproc.GaussianBlur(image,temp,new Size(5,5),5,5);
                Core.addWeighted(image,1+amount,temp,-amount,0,image);
            }
            if(invert) {
                java.util.ArrayList<Mat> channels=new java.util.ArrayList<>();Core.split(image,channels);
                try {
                    for(int i=0;i<3;i++)Core.bitwise_not(channels.get(i),channels.get(i));
                    if(channels.size()==4)channels.get(3).setTo(new org.opencv.core.Scalar(1));
                    Core.merge(channels,image);
                } finally {for(Mat channel:channels)channel.release();}
                if(android.os.Build.VERSION.SDK_INT==31 || android.os.Build.VERSION.SDK_INT==32) {
                    int background=AppState.number(context,"page_color",0xff333333);
                    if(androidx.core.graphics.ColorUtils.calculateLuminance(background)>.7)background=0xffcccccc;
                    Core.add(image,new org.opencv.core.Scalar(background>>16&255,background>>8&255,background&255,255),image);
                }
            }
            Bitmap result=Bitmap.createBitmap(image.cols(),image.rows(),invert && !source.hasAlpha() ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(image,result);
            if(blue) {
                Bitmap toned=Bitmap.createBitmap(result.getWidth(),result.getHeight(),Bitmap.Config.ARGB_8888);
                android.graphics.Paint paint=new android.graphics.Paint();
                paint.setColorFilter(new android.graphics.LightingColorFilter(blueMultiplier(invert,AppState.number(context,"blue_strength",50)),0));
                new android.graphics.Canvas(toned).drawBitmap(result,0,0,paint);result.recycle();result=toned;
            }
            return result;
        } finally {temp.release();image.release();}
    }
    private static void contrast(Mat rgba) {
        Mat yuv=new Mat(), luminance=new Mat(), sample=new Mat();
        try {
            Imgproc.cvtColor(rgba,yuv,Imgproc.COLOR_RGB2YUV);
            Core.extractChannel(yuv,luminance,0);
            int height=Math.max(1,(int)((long)rgba.rows()*128/rgba.cols()));
            Imgproc.resize(luminance,sample,new Size(128,height));
            byte[] values=new byte[(int)sample.total()];sample.get(0,0,values);
            int[] histogram=new int[256];for(byte value:values)histogram[value&255]++;
            int low=0,high=0,total=0;boolean found=false;
            for(int i=0;i<256;i++) {
                total+=histogram[i];
                if(!found && total>values.length*.05) {low=i;found=true;}
                if(total<values.length*.97)high=i;
            }
            if(low<255)Core.normalize(luminance,luminance,-low,510-high,Core.NORM_MINMAX);
            Core.insertChannel(luminance,yuv,0);Imgproc.cvtColor(yuv,rgba,Imgproc.COLOR_YUV2RGB,4);
        } finally {sample.release();luminance.release();yuv.release();}
    }
    static int blueMultiplier(boolean inverted,int level) {
        int neutral=inverted ? 54 : 95, warm=0xe4c463, result=0;
        float fraction=Math.max(0,Math.min(100,level))/100f;
        for(int shift=0;shift<=16;shift+=8) {
            int base=(neutral+(warm>>shift&255))/2;
            result|=(int)(base*(1-fraction)+255*fraction)<<shift;
        }
        return result;
    }
}
