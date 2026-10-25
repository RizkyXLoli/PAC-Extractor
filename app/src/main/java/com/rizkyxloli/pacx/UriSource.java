package com.rizkyxloli.pacx;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class UriSource implements Source {
    private final ParcelFileDescriptor pfd;
    private final FileInputStream fis;
    private final FileChannel ch;
    private final long size;
    private final String name;

    public UriSource(ContentResolver cr, Uri uri, String displayName) throws IOException {
        ParcelFileDescriptor p = cr.openFileDescriptor(uri, "r");
        if (p == null) throw new IOException("Tidak bisa membuka file yang dipilih");
        this.pfd = p;
        this.fis = new FileInputStream(p.getFileDescriptor());
        this.ch = fis.getChannel();
        this.name = displayName == null ? "firmware.pac" : displayName;
        long n;
        try {
            n = ch.size();
        } catch (IOException e) {
            n = -1;
        }
        if (n <= 0) {
            closeQuietly();
            throw new IOException("Penyimpanan ini tidak mendukung baca acak. "
                    + "Salin file .pac ke penyimpanan internal HP lalu pilih lagi.");
        }
        this.size = n;
    }

    @Override public long length() { return size; }

    @Override public void readFully(long pos, byte[] buf, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        long p = pos;
        while (bb.hasRemaining()) {
            int n = ch.read(bb, p);
            if (n <= 0) throw new IOException("File terpotong saat dibaca pada offset " + p);
            p += n;
        }
    }

    @Override public String displayName() { return name; }

    private void closeQuietly() {
        try { ch.close(); } catch (IOException ignored) { }
        try { fis.close(); } catch (IOException ignored) { }
        try { pfd.close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException {
        try { ch.close(); } finally {
            try { fis.close(); } finally { pfd.close(); }
        }
    }
}
