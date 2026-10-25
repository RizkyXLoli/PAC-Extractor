package com.rizkyxloli.pacx;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class UriSrc implements PacWriter.Src, AutoCloseable {
    private final ParcelFileDescriptor pfd;
    private final FileInputStream fis;
    private final FileChannel ch;
    private final long size;

    public UriSrc(ContentResolver cr, Uri uri) throws IOException {
        ParcelFileDescriptor p = cr.openFileDescriptor(uri, "r");
        if (p == null) throw new IOException("tidak bisa membuka file pengganti");
        this.pfd = p;
        this.fis = new FileInputStream(p.getFileDescriptor());
        this.ch = fis.getChannel();
        long n = ch.size();
        if (n <= 0) {
            close();
            throw new IOException("file pengganti tidak mendukung baca acak");
        }
        this.size = n;
    }

    @Override public long length() { return size; }

    @Override public void read(long pos, byte[] b, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        long p = pos;
        while (bb.hasRemaining()) {
            int n = ch.read(bb, p);
            if (n <= 0) throw new IOException("file pengganti terpotong pada offset " + p);
            p += n;
        }
    }

    @Override public void close() {
        try { ch.close(); } catch (IOException ignored) { }
        try { fis.close(); } catch (IOException ignored) { }
        try { pfd.close(); } catch (IOException ignored) { }
    }
}
