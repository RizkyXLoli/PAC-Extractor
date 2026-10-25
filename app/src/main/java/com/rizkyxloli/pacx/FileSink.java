package com.rizkyxloli.pacx;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/** Sink ke folder biasa — dipakai saat file tujuan berupa path (bukan SAF). */
public class FileSink implements Engine.Sink {

    private final File dir;
    private final Map<String, File> created = new HashMap<>();

    public FileSink(File dir) {
        this.dir = dir;
    }

    @Override
    public OutputStream create(String fileName) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Tidak bisa membuat folder " + dir.getAbsolutePath());
        }
        File f = unique(dir, fileName);
        created.put(fileName, f);
        return new BufferedOutputStream(new FileOutputStream(f), 256 * 1024);
    }

    @Override
    public void discard(String fileName) {
        File f = created.remove(fileName);
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    static File unique(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }
}
