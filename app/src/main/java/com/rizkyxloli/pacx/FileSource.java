package com.rizkyxloli.pacx;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Sumber dari path file biasa (dipakai di luar Android / saat file lokal). */
public class FileSource implements Source {

    private final RandomAccessFile raf;
    private final String name;

    public FileSource(File f) throws IOException {
        this.raf = new RandomAccessFile(f, "r");
        this.name = f.getName();
    }

    @Override public long length() throws IOException { return raf.length(); }

    @Override public void readFully(long pos, byte[] buf, int off, int len) throws IOException {
        raf.seek(pos);
        raf.readFully(buf, off, len);
    }

    @Override public String displayName() { return name; }

    @Override public void close() throws IOException { raf.close(); }
}
