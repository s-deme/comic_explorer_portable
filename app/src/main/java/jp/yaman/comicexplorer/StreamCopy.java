package jp.yaman.comicexplorer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies without owning the streams; callers retain close, sync and commit responsibility. */
final class StreamCopy {
    private StreamCopy() { }

    static void copy(InputStream input, OutputStream output, File space) throws IOException {
        if (input == null) throw new IOException(I18n.t(R.string.ui_cannot_open_file_2));
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException(I18n.t(R.string.ui_canceled));
            if (space != null && space.getParentFile().getUsableSpace() < count + 16 * 1024 * 1024L)
                throw new IOException(I18n.t(R.string.ui_not_enough_free_space));
            if (output != null) output.write(buffer, 0, count);
        }
    }
}
