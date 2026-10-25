package com.rizkyxloli.pacx;

import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.OutputStream;

/** Sink SAF: menulis tiap partisi sebagai file di folder yang dipilih pengguna. */
public class SafSink implements Engine.Sink {

    private final ContentResolver cr;
    private final Uri dir;
    private final java.util.Map<String, Uri> created = new java.util.HashMap<>();

    public SafSink(ContentResolver cr, Uri treeUri) {
        this.cr = cr;
        this.dir = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    }

    @Override
    public OutputStream create(String fileName) throws IOException {
        Uri doc = DocumentsContract.createDocument(cr, dir, "application/octet-stream", fileName);
        if (doc == null) throw new IOException("tidak bisa membuat file \"" + fileName + "\"");
        OutputStream os = cr.openOutputStream(doc, "w");
        if (os == null) {
            try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
            throw new IOException("tidak bisa menulis file \"" + fileName + "\"");
        }
        created.put(fileName, doc);
        return os;
    }

    @Override
    public void discard(String fileName) {
        Uri doc = created.remove(fileName);
        if (doc != null) {
            try { DocumentsContract.deleteDocument(cr, doc); } catch (Exception ignored) { }
        }
    }

    /** Tulis blok XML PAC sebagai file .xml di folder tujuan. */
    public boolean writeXml(String fileName, String xml) {
        if (xml == null || xml.isEmpty()) return false;
        try {
            Uri doc = DocumentsContract.createDocument(cr, dir, "text/xml", fileName);
            if (doc == null) return false;
            try (OutputStream os = cr.openOutputStream(doc, "w")) {
                if (os == null) return false;
                os.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                os.flush();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
