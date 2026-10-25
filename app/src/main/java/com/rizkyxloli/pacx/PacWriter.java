package com.rizkyxloli.pacx;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PacWriter {
    public interface Out extends Closeable {
        void write(byte[] b, int off, int len) throws IOException;

        void patch(long pos, byte[] b, int off, int len) throws IOException;
        void flush() throws IOException;
    }

    public interface Src {
        long length();
        void read(long pos, byte[] b, int off, int len) throws IOException;
    }

    public interface Cb {
        void log(String msg);

        boolean progress(int pct, String stage, long done, long total);
    }

    public static class Result {
        public long bytes;
        public int replaced;
        public int written;
        public boolean cancelled;
    }

    private static final int BUF = 256 * 1024;

    public static Result repack(PacParser.Pac pac, Map<Integer, Src> replacements,
                                Out out, Cb cb) throws IOException {
        Result r = new Result();
        int count = pac.fileCount;
        long tableEnd = (long) pac.fileOffset + (long) count * PacParser.ENTRY_SIZE;
        long origLen = pac.source.length();

        List<PacParser.Entry> order = new ArrayList<>();
        for (PacParser.Entry e : pac.entries) {
            if (e.size > 0 && e.dataOffset > 0) order.add(e);
        }
        order.sort((a, b) -> Long.compare(a.dataOffset, b.dataOffset));

        byte[] table = pac.rawTable.clone();
        int n = order.size();
        long[] newOffset = new long[n];
        long[] newSize = new long[n];
        long[] gapLen = new long[n];
        long[] gapSrc = new long[n];

        long cursor = tableEnd;
        long prevOrigEnd = tableEnd;
        for (int i = 0; i < n; i++) {
            PacParser.Entry e = order.get(i);
            long gap = Math.max(0, e.dataOffset - prevOrigEnd);
            gapLen[i] = gap;
            gapSrc[i] = prevOrigEnd;

            Src rep = replacements == null ? null : replacements.get(e.index);
            long sz = rep != null ? rep.length() : e.size;
            if (sz < 0) sz = 0;
            newSize[i] = sz;
            newOffset[i] = cursor + gap;
            cursor = newOffset[i] + sz;
            prevOrigEnd = e.dataOffset + e.size;

            setQword(table, e.index, 1532, 1540, sz);
            setQword(table, e.index, 1536, 1552, newOffset[i]);
            if (rep != null) r.replaced++;
        }

        long tailLen = Math.max(0, origLen - prevOrigEnd);
        long tailSrc = prevOrigEnd;
        long total = cursor + tailLen;

        byte[] hdr = pac.rawHeader.clone();
        putQword(hdr, 44, 48, total);
        int crc1 = Crc16.arc(hdr, 0, 2120, 0);
        putShort(hdr, 2120, crc1);
        putShort(hdr, 2122, 0);

        if (cb != null) {
            cb.log("menyusun " + n + " bagian, total " + PacParser.human(total)
                    + (r.replaced > 0 ? ", " + r.replaced + " diganti" : ""));
        }
        out.write(hdr, 0, hdr.length);
        out.write(table, 0, table.length);
        int crc2 = Crc16.arc(table, 0, table.length, 0);

        byte[] buf = new byte[BUF];
        long done = 0;
        boolean cancelled = false;

        for (int i = 0; i < n && !cancelled; i++) {
            PacParser.Entry e = order.get(i);
            Src rep = replacements == null ? null : replacements.get(e.index);
            String nm = Engine.fileNameFor(e);
            if (cb != null && !cb.progress(pct(done, total), nm, done, total)) {
                cancelled = true;
                break;
            }

            long gl = gapLen[i];
            long gp = gapSrc[i];
            while (gl > 0 && !cancelled) {
                int want = (int) Math.min(buf.length, gl);
                pac.source.readFully(gp, buf, 0, want);
                out.write(buf, 0, want);
                crc2 = Crc16.arc(buf, 0, want, crc2);
                gp += want;
                gl -= want;
                done += want;
                if (cb != null && !cb.progress(pct(done, total), nm, done, total)) {
                    cancelled = true;
                }
            }

            long left = newSize[i];
            long pos = 0;
            while (left > 0 && !cancelled) {
                int want = (int) Math.min(buf.length, left);
                if (rep != null) {
                    rep.read(pos, buf, 0, want);
                } else {
                    pac.source.readFully(e.dataOffset + pos, buf, 0, want);
                }
                out.write(buf, 0, want);
                crc2 = Crc16.arc(buf, 0, want, crc2);
                pos += want;
                left -= want;
                done += want;
                if (cb != null && !cb.progress(pct(done, total), nm, done, total)) {
                    cancelled = true;
                }
            }
            if (cb != null) {
                cb.log(String.format(Locale.US, "  %-20s %s%s", nm,
                        PacParser.human(newSize[i]), rep != null ? "  [diganti]" : ""));
            }
        }

        long tl = tailLen;
        long tp = tailSrc;
        while (tl > 0 && !cancelled) {
            int want = (int) Math.min(buf.length, tl);
            pac.source.readFully(tp, buf, 0, want);
            out.write(buf, 0, want);
            crc2 = Crc16.arc(buf, 0, want, crc2);
            tp += want;
            tl -= want;
            done += want;
        }

        if (!cancelled) {
            byte[] c2 = new byte[] { (byte) (crc2 & 0xFF), (byte) ((crc2 >>> 8) & 0xFF) };
            out.patch(2122, c2, 0, 2);
            out.flush();
            r.bytes = total;
            r.written = n;
            if (cb != null) {
                cb.log(String.format(Locale.US,
                        "selesai: %s, wCRC1=0x%04X wCRC2=0x%04X",
                        PacParser.human(total), crc1, crc2));
            }
        }
        r.cancelled = cancelled;
        return r;
    }

    static void putQword(byte[] b, int offHi, int offLo, long v) {
        putInt(b, offHi, (int) ((v >>> 32) & 0xFFFFFFFFL));
        putInt(b, offLo, (int) (v & 0xFFFFFFFFL));
    }

    static void setQword(byte[] table, int idx, int relHi, int relLo, long v) {
        int base = idx * PacParser.ENTRY_SIZE;
        putInt(table, base + relHi, (int) ((v >>> 32) & 0xFFFFFFFFL));
        putInt(table, base + relLo, (int) (v & 0xFFFFFFFFL));
    }

    static void putInt(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }

    static void putShort(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    static int pct(long done, long total) {
        return total <= 0 ? 0 : (int) Math.min(100, done * 100 / total);
    }
}
