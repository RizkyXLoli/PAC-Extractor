package com.rizkyxloli.pacx;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

public class Engine {
    public interface Cb {
        void log(String msg);

        boolean progress(int pct, String stage, long done, long total);
    }

    public interface Sink {
        OutputStream create(String fileName) throws IOException;

        void discard(String fileName);
    }

    public static class Outcome {
        public long bytes;
        public int ok;
        public int failed;
        public boolean cancelled;
    }

    private static final int BUF = 256 * 1024;

    public static String fileNameFor(PacParser.Entry e) {
        String n = e.name != null ? e.name.trim() : "";
        if (n.isEmpty()) n = e.id.trim();
        if (n.isEmpty()) n = e.idName.trim();
        if (n.isEmpty()) n = "entry" + e.index;
        if (n.indexOf('.') < 0) n += ".bin";
        return safeName(n, "entry" + e.index + ".bin");
    }

    public static String safeName(String raw, String fallback) {
        String s = raw == null ? "" : raw.trim();
        s = s.replace('\\', '_').replace('/', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_')
                .replace('\n', '_').replace('\r', '_');
        if (s.length() > 120) s = s.substring(0, 120);
        if (s.isEmpty()) s = fallback;
        return s;
    }

    public static Outcome extract(PacParser.Pac pac, List<PacParser.Entry> list,
                                  Sink sink, Cb cb) {
        Outcome r = new Outcome();

        long totalBytes = 0;
        int toDo = 0;
        for (PacParser.Entry e : list) {
            if (e.hasData()) { totalBytes += e.size; toDo++; }
        }
        if (toDo == 0) return r;

        long doneBytes = 0;
        int n = 0;
        for (PacParser.Entry e : list) {
            if (!e.hasData()) continue;
            n++;
            String name = fileNameFor(e);
            if (cb != null && !cb.progress(pct(doneBytes, totalBytes), name, doneBytes, totalBytes)) {
                r.cancelled = true;
                break;
            }
            if (cb != null) {
                cb.log("[" + n + "/" + toDo + "] " + name + "  " + PacParser.human(e.size));
            }
            OutputStream os = null;
            try {
                os = sink.create(name);
                if (os == null) throw new IOException("tidak bisa membuat file di folder tujuan");

                long left = e.size;
                long pos = e.dataOffset;
                byte[] buf = new byte[BUF];
                while (left > 0) {
                    int want = (int) Math.min(buf.length, left);
                    pac.source.readFully(pos, buf, 0, want);
                    os.write(buf, 0, want);
                    pos += want;
                    left -= want;
                    doneBytes += want;
                    if (cb != null && !cb.progress(pct(doneBytes, totalBytes), name,
                            doneBytes, totalBytes)) {
                        r.cancelled = true;
                        break;
                    }
                }
                os.flush();
                os.close();
                os = null;

                if (r.cancelled) {
                    sink.discard(name);
                    break;
                }
                r.ok++;
                r.bytes += e.size;
                if (cb != null) cb.log("    -> OK  " + PacParser.human(e.size));
            } catch (Exception ex) {
                r.failed++;
                String m = friendly(ex);
                if (cb != null) cb.log("    -> GAGAL: " + m);
                if (os != null) {
                    try { os.close(); } catch (IOException ignored) { }
                }
                sink.discard(name);
            }
        }
        return r;
    }

    static int pct(long done, long total) {
        return total <= 0 ? 0 : (int) Math.min(100, done * 100 / total);
    }

    public static String friendly(Exception ex) {
        String m = ex.getMessage() == null ? "" : ex.getMessage();
        String low = m.toLowerCase(java.util.Locale.ROOT);
        if (ex instanceof java.io.IOException
                && (low.contains("enospc") || low.contains("no space")
                || low.contains("space left") || low.contains("quota"))) {
            return "ruang penyimpanan tujuan habis — kosongkan dulu, lalu ulangi";
        }
        if (m.isEmpty()) return ex.getClass().getSimpleName();
        return m;
    }
}
