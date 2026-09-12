package jp.yaman.comicexplorer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

/** SMB/FTP connections are exposed through Android's document picker. */
public final class NetworkStorage {
    private NetworkStorage() { }
    static JSONObject hosts(Context context) {
        try { return new JSONObject(AppState.value(context, "hosts", "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    static void save(Context context, JSONObject hosts) {
        AppState.put(context, "hosts", hosts.toString());
        context.getContentResolver().notifyChange(DocumentsContract.buildRootsUri(context.getPackageName()+".network"), null);
    }
    public static void show(Activity activity) {
        JSONObject hosts = hosts(activity); ArrayList<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        labels.add(I18n.t(R.string.ui_add_connection));
        java.util.Iterator<String> iterator = hosts.keys();
        while (iterator.hasNext()) { String id = iterator.next(); ids.add(id); labels.add(hosts.optJSONObject(id).optString("name")); }
        Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_network_connections)).setItems(labels.toArray(new String[0]), (dialog, index) -> {
            if (index == 0) edit(activity, null);
            else {
                String id = ids.get(index-1);
                Ui.show(new AlertDialog.Builder(activity).setTitle(labels.get(index)).setItems(new String[]{I18n.t(R.string.ui_open), I18n.t(R.string.ui_edit), I18n.t(R.string.ui_delete)}, (d, i) -> {
                    if (i == 0) {
                        android.content.Intent picker = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE);
                        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(activity.getPackageName()+".network", id + ":"));
                        picker.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        activity.startActivityForResult(picker, 41);
                    } else if (i == 1) edit(activity, id);
                    else { hosts.remove(id); save(activity, hosts); }
                }));
            }
        }));
    }
    private static void edit(Activity activity, String existingId) {
        JSONObject hosts = hosts(activity), old = existingId == null ? new JSONObject() : hosts.optJSONObject(existingId);
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(Ui.dp(activity, 16), 0, Ui.dp(activity, 16), 0);
        Spinner protocol = new Spinner(activity); protocol.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, new String[]{"SMB", "FTP", "FTPS"}));
        protocol.setSelection(old.optInt("protocol", 0)); form.addView(protocol);
        String[] keys = {"name", "host", "port", "share", "path", "user", "password", "domain"};
        String[] labels = {I18n.t(R.string.ui_name), I18n.t(R.string.ui_host_name), I18n.t(R.string.ui_port_blank_for_default), I18n.t(R.string.ui_share_name_smb), I18n.t(R.string.ui_path), I18n.t(R.string.ui_userid), I18n.t(R.string.ui_passwd), I18n.t(R.string.ui_domain_smb)};
        EditText[] fields = new EditText[keys.length];
        for (int i=0; i<keys.length; i++) { fields[i]=new EditText(activity); fields[i].setSingleLine(true); fields[i].setHint(labels[i]); fields[i].setContentDescription(labels[i]); fields[i].setText(keys[i].equals("password") ? "" : old.optString(keys[i])); form.addView(fields[i]); }
        fields[6].setInputType(129); fields[2].setInputType(2);
        android.widget.CheckBox passive = new android.widget.CheckBox(activity); passive.setText(I18n.t(R.string.ui_passive_ftp_off_active)); passive.setChecked(old.optBoolean("passive", true)); form.addView(passive);
        ScrollView scroll = new ScrollView(activity); scroll.addView(form);
        AlertDialog dialog = Ui.show(new AlertDialog.Builder(activity).setTitle(I18n.t(R.string.ui_connection)).setView(scroll).setNegativeButton(I18n.t(R.string.ui_cancel), null).setPositiveButton(I18n.t(R.string.ui_save), null));
        dialog.getButton(-1).setOnClickListener(v -> {
            try {
                String host = fields[1].getText().toString().trim();
                if (host.isEmpty() || host.contains("/") || host.contains("@") || host.contains("\n")) throw new IllegalArgumentException(I18n.t(R.string.ui_enter_a_host_name));
                if (protocol.getSelectedItemPosition() == 0 && fields[3].getText().toString().trim().isEmpty()) throw new IllegalArgumentException(I18n.t(R.string.ui_enter_a_share_name));
                String port = fields[2].getText().toString().trim(); if (!port.isEmpty() && (Integer.parseInt(port)<1 || Integer.parseInt(port)>65535)) throw new IllegalArgumentException(I18n.t(R.string.ui_port_must_be_between_1_and_65535));
                JSONObject record = new JSONObject(); record.put("protocol", protocol.getSelectedItemPosition()); record.put("passive", passive.isChecked());
                for (int i=0; i<keys.length; i++) {
                    String value = fields[i].getText().toString();
                    if (keys[i].equals("password")) value = value.isEmpty() ? old.optString("password") : encrypt(value);
                    else if (value.contains("\n") || value.contains("\r")) throw new IllegalArgumentException(I18n.t(R.string.ui_line_breaks_are_not_allowed));
                    record.put(keys[i], value);
                }
                if (record.optString("name").trim().isEmpty()) record.put("name", host);
                validatePath(record.optString("path"));
                hosts.put(existingId == null ? java.util.UUID.randomUUID().toString() : existingId, record); save(activity, hosts); dialog.dismiss();
            } catch (Exception e) { fields[1].setError(e.getMessage()); }
        });
    }
    static void validatePath(String path) {
        if (path.indexOf(0)>=0 || path.contains("\r") || path.contains("\n")) throw new IllegalArgumentException(I18n.t(R.string.ui_invalid_path));
        for (String segment : path.replace('\\','/').split("/")) if (segment.equals("..")) throw new IllegalArgumentException(I18n.t(R.string.ui_invalid_path));
    }
    private static javax.crypto.SecretKey key() throws Exception {
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "comic-network-password";
        if (!store.containsAlias(alias)) {
            javax.crypto.KeyGenerator generator = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build()); generator.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey(alias, null);
    }
    private static String encrypt(String value) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(1, key());
        return android.util.Base64.encodeToString(cipher.getIV(), 2) + ":" + android.util.Base64.encodeToString(cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)), 2);
    }
    private static String decrypt(String value) throws Exception {
        if (value.isEmpty()) return "";
        String[] parts = value.split(":"); if (parts.length != 2) throw new IOException(I18n.t(R.string.ui_enter_the_connection_password_again));
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(2, key(), new javax.crypto.spec.GCMParameterSpec(128, android.util.Base64.decode(parts[0], 2)));
        return new String(cipher.doFinal(android.util.Base64.decode(parts[1], 2)), java.nio.charset.StandardCharsets.UTF_8);
    }
    static final class Entry {
        final String name; final boolean directory; final long size, modified;
        Entry(String name, boolean directory, long size, long modified) { this.name=name; this.directory=directory; this.size=size; this.modified=modified; }
    }
    static final class Remote implements AutoCloseable {
        SMBClient client; Connection connection; Session session; DiskShare share; FTPClient ftp;
        final String base;
        Remote(JSONObject host) throws Exception {
            base = host.optString("path").replace('\\','/').replaceAll("^/+|/+$", ""); validatePath(base);
            int protocol = host.optInt("protocol"); String rawPort=host.optString("port"); int port=rawPort.isEmpty() ? protocol==0 ? 445 : 21 : Integer.parseInt(rawPort);
            String user=host.optString("user"), password=decrypt(host.optString("password"));
            try {
                if (protocol == 0) {
                    client = new SMBClient(SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).withSoTimeout(30, TimeUnit.SECONDS).build());
                    connection = client.connect(host.getString("host"), port);
                    session = connection.authenticate(user.isEmpty() ? AuthenticationContext.anonymous() : new AuthenticationContext(user, password.toCharArray(), host.optString("domain")));
                    share = (DiskShare) session.connectShare(host.getString("share"));
                } else {
                    ftp = protocol == 2 ? new org.apache.commons.net.ftp.FTPSClient() : new FTPClient();
                    if (ftp instanceof org.apache.commons.net.ftp.FTPSClient) {
                        org.apache.commons.net.ftp.FTPSClient secure = (org.apache.commons.net.ftp.FTPSClient)ftp;
                        secure.setTrustManager(null); // Use platform CA validation, not the library's validity-only manager.
                        secure.setEndpointCheckingEnabled(true);
                    }
                    ftp.setConnectTimeout(15000); ftp.setDefaultTimeout(30000); ftp.setDataTimeout(java.time.Duration.ofSeconds(30)); ftp.setControlEncoding("UTF-8");
                    ftp.connect(host.getString("host"), port);
                    if (!ftp.login(user.isEmpty() ? "anonymous" : user, password)) throw new IOException(I18n.t(R.string.ui_ftp_authentication_failed));
                    ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE);
                    if (ftp instanceof org.apache.commons.net.ftp.FTPSClient) { ((org.apache.commons.net.ftp.FTPSClient)ftp).execPBSZ(0); ((org.apache.commons.net.ftp.FTPSClient)ftp).execPROT("P"); }
                    if (host.optBoolean("passive", true)) ftp.enterLocalPassiveMode(); else ftp.enterLocalActiveMode();
                }
            } catch (Exception e) { close(); throw e; }
        }
        String path(String relative) { validatePath(relative); String path = base + (base.isEmpty() || relative.isEmpty() ? "" : "/") + relative; return share == null ? "/"+path : path.replace('/', '\\'); }
        ArrayList<Entry> list(String relative) throws IOException {
            ArrayList<Entry> result = new ArrayList<>();
            if (share != null) {
                for (com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation item : share.list(path(relative)))
                    if (!item.getFileName().equals(".") && !item.getFileName().equals("..")) result.add(new Entry(item.getFileName(), (item.getFileAttributes() & 16)!=0, item.getEndOfFile(), item.getLastWriteTime().toEpochMillis()));
            } else {
                FTPFile[] files = ftp.listFiles(path(relative)); if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(ftp.getReplyCode())) throw new IOException(I18n.t(R.string.ui_cannot_list_ftp_directory));
                for (FTPFile item : files) if (!item.getName().equals(".") && !item.getName().equals("..")) result.add(new Entry(item.getName(), item.isDirectory(), item.getSize(), item.getTimestamp()==null ? 0 : item.getTimestamp().getTimeInMillis()));
            }
            return result;
        }
        void download(String relative, java.io.File file) throws Exception {
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (share != null) {
                    try (com.hierynomus.smbj.share.File remote = share.openFile(path(relative), EnumSet.of(AccessMask.FILE_READ_DATA), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null); InputStream input=remote.getInputStream()) { copy(input, output, file); }
                } else {
                    try (InputStream input=ftp.retrieveFileStream(path(relative))) { if (input==null) throw new IOException(I18n.t(R.string.ui_cannot_open_ftp_file)); copy(input, output, file); }
                    if (!ftp.completePendingCommand()) throw new IOException(I18n.t(R.string.ui_ftp_transfer_did_not_complete));
                }
            }
        }
        private void copy(InputStream input, FileOutputStream output, java.io.File file) throws IOException {
            byte[] buffer=new byte[65536]; int count;
            while ((count=input.read(buffer))!=-1) { if (Thread.currentThread().isInterrupted() || file.getParentFile().getUsableSpace()<count+16*1024*1024L) throw new IOException(I18n.t(R.string.ui_cannot_continue_transfer)); output.write(buffer,0,count); }
            output.getFD().sync();
        }
        void mkdir(String relative) throws IOException { if (share!=null) share.mkdir(path(relative)); else if (!ftp.makeDirectory(path(relative))) throw new IOException(I18n.t(R.string.ui_cannot_create_folder)); }
        void delete(String relative, boolean directory) throws IOException {
            if (relative.isEmpty()) throw new IOException(I18n.t(R.string.ui_cannot_delete_connection_root));
            if (share!=null) { if (directory) share.rmdir(path(relative), true); else share.rm(path(relative)); }
            else { if (directory) { for (Entry entry:list(relative)) delete(relative+"/"+entry.name, entry.directory); if (!ftp.removeDirectory(path(relative))) throw new IOException(I18n.t(R.string.ui_cannot_delete_folder)); } else if (!ftp.deleteFile(path(relative))) throw new IOException(I18n.t(R.string.ui_cannot_delete_file)); }
        }
        void rename(String from, String to, boolean directory) throws IOException {
            if (from.isEmpty() || to.isEmpty()) throw new IOException(I18n.t(R.string.ui_cannot_modify_connection_root));
            if (share!=null) {
                try (com.hierynomus.smbj.share.DiskEntry entry = directory
                        ? share.openDirectory(path(from), EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                        : share.openFile(path(from), EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)) { entry.rename(path(to), false); }
            } else if (!ftp.rename(path(from), path(to))) throw new IOException(I18n.t(R.string.ui_cannot_rename));
        }
        @Override public void close() {
            try { if (share!=null) share.close(); } catch (Exception ignored) { }
            try { if (session!=null) session.close(); } catch (Exception ignored) { }
            try { if (connection!=null) connection.close(); } catch (Exception ignored) { }
            try { if (client!=null) client.close(); } catch (Exception ignored) { }
            try { if (ftp!=null && ftp.isConnected()) ftp.disconnect(); } catch (Exception ignored) { }
        }
    }
}
