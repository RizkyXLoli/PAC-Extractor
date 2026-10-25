package com.rizkyxloli.pacx;

import java.io.Closeable;
import java.io.IOException;

/**
 * Sumber data acak (seekable) untuk file .pac.
 *
 * Android memberi file lewat content:// (SAF), bukan path. Karena itu pembacaan
 * tidak boleh bergantung pada java.io.File saja. Dua implementasi:
 *   - FileSource : path biasa (RandomAccessFile)
 *   - UriSource  : content:// lewat ParcelFileDescriptor + FileChannel
 */
public interface Source extends Closeable {

    long length() throws IOException;

    /** Baca tepat len byte mulai posisi pos. */
    void readFully(long pos, byte[] buf, int off, int len) throws IOException;

    String displayName();
}
