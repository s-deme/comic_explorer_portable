package jp.yaman.comicexplorer;

import net.sf.sevenzipjbinding.*;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;
import java.io.*;
import java.util.*;

/** Stream entries to bounded private files; archive names never become output paths. */
final class ArchivePages implements AutoCloseable {
    static final long LIMIT=48L*1024*1024;
    final ArrayList<String> names=new ArrayList<>();
    private final Map<String,Integer> indices=new HashMap<>();
    private final ArrayList<RandomAccessFileInStream> streams=new ArrayList<>();
    private IInArchive archive;
    private final String password;
    private boolean passwordRequested;
    private String missingVolume;
    static final class PasswordRequired extends IOException { PasswordRequired(){super("Password required or incorrect");} }
    static final class MissingVolume extends IOException { MissingVolume(String name){super(name);} }
    ArchivePages(File file,String title,String password,Map<String,File> volumes) throws IOException {
        this.password=password;
        if(title.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(7z|cb7)")) {
            try(org.apache.commons.compress.archivers.sevenz.SevenZFile checked=org.apache.commons.compress.archivers.sevenz.SevenZFile.builder().setFile(file).setPassword(password==null ? (char[])null : password.toCharArray()).setMaxMemoryLimitKiB(64*1024).get()) { }
            catch(org.apache.commons.compress.PasswordRequiredException required){throw new PasswordRequired();}
            catch(org.apache.commons.compress.MemoryLimitException tooLarge){throw tooLarge;}
            catch(IOException invalid){if(password!=null)throw new PasswordRequired();throw invalid;}
        }
        class Open implements IArchiveOpenCallback,IArchiveOpenVolumeCallback,ICryptoGetTextPassword {
            public void setTotal(Long files,Long bytes) { }
            public void setCompleted(Long files,Long bytes) { }
            public Object getProperty(PropID id) {return id==PropID.NAME ? title : null;}
            public IInStream getStream(String name) throws SevenZipException {
                if(name==null || name.contains("/") || name.contains("\\") || name.equals(".."))throw new SevenZipException("Invalid volume name");
                File next=name.equals(title) ? file : volumes.get(name);
                if(next==null) {missingVolume=name;return null;}
                try{return stream(next);}catch(IOException e){throw new SevenZipException(e);}
            }
            public String cryptoGetTextPassword() throws SevenZipException {
                passwordRequested=true;if(password==null)throw new SevenZipException("Password required");return password;
            }
        }
        try {
            Open callback=new Open();
            IInStream input=title.toLowerCase(java.util.Locale.ROOT).endsWith(".001") ? new net.sf.sevenzipjbinding.impl.VolumedArchiveInStream(title,callback) : stream(file);
            String lower=title.toLowerCase(java.util.Locale.ROOT);
            ArchiveFormat format=lower.endsWith(".7z.001") ? ArchiveFormat.SEVEN_ZIP : lower.endsWith(".zip.001") ? ArchiveFormat.ZIP : null;
            archive=SevenZip.openInArchive(format,input,callback);
            if(archive.getNumberOfItems()>100000)throw new IOException("Too many archive entries");
            for(int i=0;i<archive.getNumberOfItems();i++) {
                String name=archive.getStringProperty(i,PropID.PATH);
                if(name!=null)name=name.replace('\\','/');
                if(Boolean.TRUE.equals(archive.getProperty(i,PropID.IS_FOLDER)) || !ComicFile.isImage(name,null))continue;
                if(names.size()>=20000)throw new IOException(I18n.t(R.string.ui_the_archive_exceeds_the_limit_of_20_000_pages));
                if(indices.put(name,i)!=null)throw new IOException("Duplicate archive entry");names.add(name);
            }
            names.sort(ComicFile.NATURAL_NAME_ORDER);
        } catch(Exception e) {
            try{close();}catch(IOException suppressed){e.addSuppressed(suppressed);}
            throw failure(e);
        }
    }
    private RandomAccessFileInStream stream(File file) throws IOException {
        RandomAccessFileInStream stream=new RandomAccessFileInStream(new RandomAccessFile(file,"r"));streams.add(stream);return stream;
    }
    void extract(String name,File destination) throws IOException {
        Integer index=indices.get(name);if(index==null)throw new IOException(I18n.t(R.string.ui_page_not_found_in_archive));
        boolean encrypted=false;
        try(OutputStream output=new FileOutputStream(destination)) {
            encrypted=Boolean.TRUE.equals(archive.getProperty(index,PropID.ENCRYPTED));
            if(encrypted && password==null)throw new PasswordRequired();
            Object size=archive.getProperty(index,PropID.SIZE);
            if(size instanceof Number && ((Number)size).longValue()>LIMIT)throw new IOException(I18n.t(R.string.ui_image_exceeds_the_48_mb_limit));
            Object dictionary=archive.getProperty(index,PropID.DICTIONARY_SIZE);
            if(dictionary instanceof Number && ((Number)dictionary).longValue()>64L*1024*1024)throw new IOException("Archive dictionary exceeds 64 MiB");
            long[] written={0};
            ExtractOperationResult result=archive.extractSlow(index,data -> {
                if(Thread.currentThread().isInterrupted() || (written[0]+=data.length)>LIMIT)throw new SevenZipException("Page size limit exceeded");
                try {output.write(data);return data.length;}catch(IOException e){throw new SevenZipException(e);}
            },password==null ? "" : password);
            if(result!=ExtractOperationResult.OK) {
                if(missingVolume!=null)throw new MissingVolume(missingVolume);
                if(encrypted)throw new PasswordRequired();
                throw new IOException("Archive extraction: "+result);
            }
        } catch(Exception e) {throw failure(e);}
    }
    private IOException failure(Exception e) {
        if(e instanceof PasswordRequired || e instanceof MissingVolume)return (IOException)e;
        if(passwordRequested)return new PasswordRequired();
        if(missingVolume!=null)return new MissingVolume(missingVolume);
        return e instanceof IOException ? (IOException)e : new IOException(I18n.t(R.string.ui_archive_error),e);
    }
    @Override public void close() throws IOException {
        try {if(archive!=null){IInArchive old=archive;archive=null;old.close();}}
        finally {for(RandomAccessFileInStream stream:streams)try{stream.close();}catch(IOException ignored){}streams.clear();}
    }
}
