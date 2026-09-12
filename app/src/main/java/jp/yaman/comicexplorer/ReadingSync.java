package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Only reading positions and content hashes are uploaded to the private Drive app-data folder. */
public final class ReadingSync {
    private static final int AUTHORIZE = 81;
    private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static boolean busy;
    private static volatile int generation;
    static synchronized void disable(Context context) { generation++; AppState.put(context,"sync_enabled",false); }
    private ReadingSync() { }
    public static void show(Activity activity) {
        Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_sync_resume_page_beta))
                .setMessage(I18n.t(R.string.ui_sync_book_identifiers_and_reading_positions_in_private_google_drive))
                .setNegativeButton(I18n.t(R.string.ui_close), null).setNeutralButton(I18n.t(R.string.ui_turn_sync_off), (d,i) -> disable(activity))
                .setPositiveButton(I18n.t(R.string.ui_sync_with_google_drive), (d,i) -> authorize(activity, true)));
    }
    public static void authorize(Activity activity, boolean interactive) {
        if (busy || !interactive && !AppState.enabled(activity,"sync_enabled",false)) return;
        int requestGeneration = generation;
        Identity.getAuthorizationClient(activity).authorize(AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope("https://www.googleapis.com/auth/drive.appdata"))).build())
                .addOnSuccessListener(result -> {
                    if (activity.isFinishing() || requestGeneration != generation) return;
                    if (result.hasResolution()) {
                        if (!interactive) return;
                        try { activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(), AUTHORIZE, null, 0, 0, 0); }
                        catch (Exception e) { message(activity,I18n.t(R.string.ui_cannot_start_google_authorization)); }
                    } else start(activity,result.getAccessToken(),interactive);
                }).addOnFailureListener(e -> { if(interactive) message(activity,I18n.t(R.string.ui_google_authorization_failed_check_the_app_oauth_registration_and_google)); });
    }
    public static boolean result(Activity activity,int request,int resultCode,Intent data) {
        if (request!=AUTHORIZE) return false;
        if (resultCode!=Activity.RESULT_OK || data==null) return true;
        try { AuthorizationResult result=Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data); start(activity,result.getAccessToken(),true); }
        catch (Exception e) { message(activity,I18n.t(R.string.ui_google_authorization_did_not_complete)); }
        return true;
    }
    private static void start(Activity activity,String token,boolean interactive) {
        if(token==null || token.isEmpty() || busy) return;
        busy=true;
        int requestGeneration = generation;
        WORKER.execute(() -> {
            String error=null;
            try { synchronize(activity.getApplicationContext(),token,requestGeneration); }
            catch(Exception e) { error=I18n.t(R.string.ui_sync_failed_check_your_connection_and_google_drive_permissions); }
            String failure=error;
            activity.runOnUiThread(() -> { busy=false; if(requestGeneration != generation) return; if(failure==null) AppState.put(activity,"sync_enabled",true); if(interactive || failure!=null) message(activity,failure==null ? I18n.t(R.string.ui_reading_positions_synced) : failure); });
        });
    }
    private static void synchronize(Context context,String token,int requestGeneration) throws Exception {
        for(AppState.SavedItem item:AppState.recents(context)) {

            // ponytail: rehash recent books each sync; revision-aware caching if large libraries make this slow.
            {
                try(InputStream input=context.getContentResolver().openInputStream(item.uri)) {
                    if(input==null) continue;
                    java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[65536]; int count;
                    while((count=input.read(buffer))!=-1) digest.update(buffer,0,count);
                    StringBuilder id=new StringBuilder(); for(byte value:digest.digest()) id.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
                    synchronized(ReadingSync.class) {
                        if(requestGeneration!=generation) return;
                        AppState.identifyForSync(context,item,id.toString());
                    }
                } catch(java.io.IOException | SecurityException ignored) { }
            }
        }
        String device=AppState.syncDevice(context);
        String ownName="progress-v1-"+device+".json", ownId=null, next="";
        JSONObject merged=new JSONObject();
        do {
            String endpoint="https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&pageSize=1000&fields=nextPageToken,files(id,name)&q="+URLEncoder.encode("trashed=false", "UTF-8")+(next.isEmpty()?"":"&pageToken="+URLEncoder.encode(next,"UTF-8"));
            JSONObject listing=new JSONObject(request(token,endpoint,"GET",null)); JSONArray files=listing.optJSONArray("files");
            if(files!=null) for(int i=0;i<files.length();i++) {
                JSONObject file=files.getJSONObject(i); String name=file.optString("name"), id=file.getString("id");
                if(!name.startsWith("progress-v1-") || !name.endsWith(".json")) continue;
                String json=request(token,"https://www.googleapis.com/drive/v3/files/"+Uri.encode(id)+"?alt=media","GET",null);
                if(!json.trim().isEmpty()) merge(merged,new JSONObject(json));
                if(name.equals(ownName)) ownId=id;
            }
            next=listing.optString("nextPageToken");
        } while(!next.isEmpty());
        synchronized(ReadingSync.class) {
            if(requestGeneration!=generation) return;
            merge(merged,AppState.syncRecords(context));
            AppState.applySyncedProgress(context,merged);
        }
        if(ownId==null) ownId=new JSONObject(request(token,"https://www.googleapis.com/drive/v3/files","POST",new JSONObject().put("name",ownName).put("parents",new JSONArray().put("appDataFolder")).toString())).getString("id");
        request(token,"https://www.googleapis.com/upload/drive/v3/files/"+Uri.encode(ownId)+"?uploadType=media","PATCH",merged.toString());
    }
    static void merge(JSONObject into,JSONObject other) throws Exception {
        java.util.Iterator<String> keys=other.keys();
        while(keys.hasNext()) {
            String key=keys.next(); JSONObject value=other.optJSONObject(key);
            if(!key.matches("[0-9a-f]{64}") || value==null) continue;
            int page=value.optInt("page",-1), total=value.optInt("total",-1); long updated=value.optLong("updated",-1);
            if(page<0 || total<0 || total>0 && page>=total || updated<0) continue;
            JSONObject previous=into.optJSONObject(key);
            if(previous==null || updated>previous.optLong("updated")) into.put(key,new JSONObject().put("page",page).put("total",total).put("updated",updated));
        }
    }
    private static String request(String token,String url,String method,String data) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestMethod(method); connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(data!=null) { connection.setDoOutput(true); connection.setRequestProperty("Content-Type","application/json; charset=UTF-8"); try(java.io.OutputStream output=connection.getOutputStream()) { output.write(data.getBytes(StandardCharsets.UTF_8)); } }
            int status=connection.getResponseCode(); if(status<200 || status>=300) throw new java.io.IOException("Drive HTTP "+status);
            try(InputStream input=connection.getInputStream(); ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192]; int count;
                while((count=input.read(buffer))!=-1) { if(output.size()+count>4*1024*1024) throw new java.io.IOException(I18n.t(R.string.ui_sync_data_is_too_large)); output.write(buffer,0,count); }
                return new String(output.toByteArray(),StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    private static void message(Activity activity,String value) { if(!activity.isFinishing()) Toast.makeText(activity,value,Toast.LENGTH_LONG).show(); }
}
