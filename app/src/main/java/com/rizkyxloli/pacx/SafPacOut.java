package com.rizkyxloli.pacx;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class SafPacOut implements PacWriter.Out {
    private final ParcelFileDescriptor pfd;
    private final FileOutputStream fos;
    private final FileChannel ch;
    private final ContentResolver cr;
    private final Uri doc;

    public SafPacOut(ContentResolver cr, Uri treeUri, String fileName) throws IOException {
        this.cr = cr;
        Uri dir = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        Uri d = DocumentsContract.createDocument(cr, dir, "application/octet-stream", fileName);
        if (d == null) throw new IOException("tidak bisa membuat file \"" + fileName + "\" di folder tujuan");
        this.doc = d;
        ParcelFileDescriptor p = cr.openFileDescriptor(d, "rw");
        if (p == null) {
            try { DocumentsContract.deleteDocument(cr, d); } catch (Exception ignored) { }
            throw new IOException("tidak bisa membuka file \"" + fileName + "\" untuk ditulis");
        }
        this.pfd = p;
        this.fos = new FileOutputStream(p.getFileDescriptor());
        this.ch = fos.getChannel();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        while (bb.hasRemaining()) ch.write(bb);
    }

    @Override
    public void patch(long pos, byte[] b, int off, int len) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(b, off, len);
        long p = pos;
        while (bb.hasRemaining()) {
            int n = ch.write(bb, p);
            if (n <= 0) throw new IOException("penyimpanan tujuan tidak mendukung penambalan di posisi tertentu");
            p += n;
        }
    }

    @Override
    public void flush() throws IOException {
        ch.force(true);
    }

    @Override
    public void close() throws IOException {
        try { ch.close(); } finally {
            try { fos.close(); } finally { pfd.close(); }
        }
    }

    public void discard() {
        try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
    }

    public String displayName() {
        return doc.getLastPathSegment();
    }
}
