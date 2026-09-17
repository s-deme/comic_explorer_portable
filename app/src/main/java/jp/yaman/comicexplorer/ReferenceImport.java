package jp.yaman.comicexplorer;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;

/** Reads a user-selected copy. Never opens another application's private storage. */
final class ReferenceImport {
    final LinkedHashMap<String,Object> settings=new LinkedHashMap<>();
    final ArrayList<AppState.SavedItem> history=new ArrayList<>();
    final ArrayList<Entry> entries=new ArrayList<>();
    int skipped;
    static final class Entry {
        String table,title,memo; Uri uri; int page;
    }
    int count() {return settings.size()+history.size()+entries.size();}
    static ReferenceImport read(Context context,Uri source) throws Exception {
        File copy=File.createTempFile("reference-", ".data",context.getCacheDir());
        try {
            try(InputStream input=context.getContentResolver().openInputStream(source);FileOutputStream output=new FileOutputStream(copy)) {
                if(input==null)throw new IOException("Missing input");
                byte[] buffer=new byte[65536];int read,total=0;
                while((read=input.read(buffer))!=-1) {total+=read;if(total>32*1024*1024)throw new IOException("File limit exceeded");output.write(buffer,0,read);}
            }
            byte[] header=new byte[16];try(InputStream input=new java.io.FileInputStream(copy)){input.read(header);}
            ReferenceImport plan=new ReferenceImport();
            if(new String(header,java.nio.charset.StandardCharsets.US_ASCII).startsWith("SQLite format 3"))plan.database(copy);
            else plan.xml(copy);
            if(plan.count()==0)throw new IOException("No supported records");
            return plan;
        } finally {copy.delete();}
    }
    private void xml(File file) throws Exception {
        if(file.length()>2*1024*1024)throw new IOException("XML limit exceeded");
        String xml=new String(java.nio.file.Files.readAllBytes(file.toPath()),java.nio.charset.StandardCharsets.UTF_8);
        if(xml.contains("<!") || xml.indexOf('\0')>=0)throw new IOException("Unsupported XML declaration");
        XmlPullParser parser=Xml.newPullParser();parser.setInput(new java.io.StringReader(xml));
        if(parser.nextTag()!=XmlPullParser.START_TAG || !"map".equals(parser.getName()))throw new IOException("Expected preferences map");
        while(parser.next()!=XmlPullParser.END_DOCUMENT) {
            if(parser.getEventType()!=XmlPullParser.START_TAG)continue;
            String name=parser.getAttributeValue(null,"name"),value=parser.getAttributeValue(null,"value"),tag=parser.getName();
            String key=null;int maximum=0;
            if(name==null) {skipped++;continue;}
            switch(name) {
                case "set_img_filter_gray_yn":key="filter_gray";break;
                case "set_img_filter_contrast_yn":key="filter_contrast";break;
                case "set_img_filter_upscale_yn":key="filter_upscale";break;
                case "set_img_filter_sharp_yn":key="filter_sharp";break;
                case "set_img_filter_invert_yn":key="filter_invert";break;
                case "set_img_filter_night_yn":key="filter_blue";break;
                case "set_img_filter_upscale_mode":key="filter_interpolation";maximum=2;break;
                case "set_img_filter_sharp_level":key="sharp_strength";maximum=20;break;
                case "set_img_filter_night_level":key="blue_strength";maximum=100;break;
                case "set_img_doubleTap_mode":key="double_tap_mode";maximum=3;break;
                case "set_img_doubleTap_scale":key="double_tap_scale";maximum=600;break;
                default:skipped++;continue;
            }
            if(maximum==0 && "boolean".equals(tag) && ("true".equals(value)||"false".equals(value)))settings.put(key,Boolean.valueOf(value));
            else if(maximum>0 && "int".equals(tag)) {
                int number=Integer.parseInt(value);
                if(number<0 || number>maximum || key.equals("double_tap_scale") && number<100)throw new IOException("Invalid preference value");
                if(key.equals("double_tap_mode"))number=new int[]{0,2,1,3}[number];
                settings.put(key,number);
            } else throw new IOException("Preference type mismatch");
        }
    }
    private void database(File file) throws Exception {
        try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY|SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
            for(String table:new String[]{"TB_HISTORY","TB_BOOKMARK","TB_FAVORITE"}) {
                try(Cursor exists=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",new String[]{table})) {if(!exists.moveToFirst())continue;}
                try(Cursor rows=db.query(table,null,null,null,null,null,"TIMESTAMP DESC","5001")) {
                    if(rows.getCount()>5000)throw new IOException("Too many records");
                    HashSet<String> seen=new HashSet<>();
                    while(rows.moveToNext()) {
                        String title=text(rows,"NAME"),raw=text(rows,"CONTENTURI");
                        if(raw.isEmpty())raw=text(rows,"PATH");
                        Uri uri=raw.startsWith("/") ? Uri.fromFile(new File(raw)) : Uri.parse(raw);
                        if(title.isEmpty() || title.length()>4096 || raw.length()>8192 || !ComicFile.isSupported(title,null)
                                || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) {skipped++;continue;}
                        int total=number(rows,"FULLPAGE",0),page=number(rows,"INFOPAGE",Math.max(0,number(rows,"VIEWPAGE",1)-1));
                        if(page<0 || page>=20000 || total<0 || total>20000 || total>0 && page>=total) {skipped++;continue;}
                        String id=uri+ (table.equals("TB_BOOKMARK") ? ":"+page : "");
                        if(!seen.add(id)) {skipped++;continue;}
                        if(table.equals("TB_HISTORY")) {
                            int column=rows.getColumnIndex("TIMESTAMP");long timestamp=column<0 ? 0 : rows.getLong(column);
                            history.add(new AppState.SavedItem(uri,title,ComicFile.kindFor(title,null),Math.max(0,timestamp),page,total));
                        } else {
                            Entry item=new Entry();item.table=table;item.title=title;item.uri=uri;item.page=page;item.memo=text(rows,"REMARK");
                            if(item.memo.length()>4096)throw new IOException("Memo limit exceeded");entries.add(item);
                        }
                    }
                }
            }
        }
    }
    void apply(Context context) {
        android.content.SharedPreferences.Editor editor=AppState.prefs(context).edit();
        for(java.util.Map.Entry<String,Object> entry:settings.entrySet()) {
            if(entry.getValue() instanceof Boolean)editor.putBoolean("setting."+entry.getKey(),(Boolean)entry.getValue());
            else editor.putInt("setting."+entry.getKey(),(Integer)entry.getValue());
        }
        editor.apply();AppState.importHistory(context,history);
        for(Entry item:entries) {
            if(item.table.equals("TB_FAVORITE")) {if(!AppState.isFavorite(context,item.uri))AppState.setFavorite(context,item.uri,item.title,ComicFile.kindFor(item.title,null),true);}
            else if(!AppState.hasBookmark(context,item.uri,item.page)) {
                AppState.setBookmark(context,item.uri,item.page,true,item.title,ComicFile.kindFor(item.title,null));
                if(!item.memo.isEmpty())AppState.setBookmarkMemo(context,item.uri,item.page,item.memo);
            }
        }
    }
    private static String text(Cursor row,String name) {int i=row.getColumnIndex(name);return i<0||row.isNull(i)?"":row.getString(i);}
    private static int number(Cursor row,String name,int fallback) {int i=row.getColumnIndex(name);return i<0||row.isNull(i)?fallback:row.getInt(i);}
}
