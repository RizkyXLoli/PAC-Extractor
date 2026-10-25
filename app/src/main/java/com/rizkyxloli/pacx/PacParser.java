package com.rizkyxloli.pacx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser container firmware .pac (Unisoc / Spreadtrum).
 *
 * Layout on-disk (little endian, packed):
 *   [0]           PacHeader  2124 byte
 *   [fileOffset]  PacFileEntry[fileCount]  2580 byte / entri
 *   [xmlStart]    blok XML konfigurasi (UTF-8 / UTF-16)
 *   [dataOffset]  blob biner tiap entri (tidak dikompresi)
 *
 * Catatan penting: field size dan dataOffset adalah 64-bit yang dipecah dua
 * dword 32-bit (high dword di 1532/1536, low dword di 1540/1552). Membaca
 * hanya dword rendah membuat offset salah untuk PAC di atas 4 GiB.
 */
public class PacParser {

    public static final long PAC_MAGIC = 0xFFFAFFFAL;
    public static final int HDR_SIZE = 2124;
    public static final int ENTRY_SIZE = 2580;

    public static class Entry {
        public int index;
        public String id = "";
        public String name = "";
        public String version = "";
        public long size;
        public long dataOffset;
        public int fileFlag;
        public int checkFlag;
        public int omitFlag;
        public int addrCount;
        public final long[] addr = new long[5];
        // dari XML
        public String idName = "";
        public String type = "";
        public long baseAddress = -1;
        public long blockSize = 0;

        /** Entri ini punya data yang bisa diekstrak (partisi, loader, dll). */
        public boolean hasData() {
            return fileFlag != 0 && fileFlag != 2 && size > 0 && dataOffset > 0;
        }

        /** Loader FDL (bukan partisi) — tetap bisa diekstrak. */
        public boolean isLoader() {
            return fileFlag == 257;
        }

        /** Entri ini blok XML, bukan partisi. */
        public boolean isXml() {
            return fileFlag == 2;
        }

        public String label() {
            if (!idName.isEmpty()) return idName;
            if (!id.isEmpty()) return id;
            if (!name.isEmpty()) return name;
            return "entry" + index;
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(label());
            if (!type.isEmpty()) sb.append("  [").append(type).append(']');
            if (size > 0) sb.append("  ").append(human(size));
            return sb.toString();
        }
    }

    public static class Pac {
        public String version = "";
        public String productName = "";
        public String productVersion = "";
        public String productAlias = "";
        public long size;
        public int fileCount;
        public int fileOffset;
        public int mode;
        public int flashType;
        public String xml = "";
        public long xmlOffset = -1;
        public long xmlSize = 0;
        public final List<Entry> entries = new ArrayList<>();
        public Source source;

        /** Byte mentah header (2124 B) — dipakai untuk repack identik. */
        public byte[] rawHeader;
        /** Byte mentah tabel entri (2580 B x fileCount) — dipakai untuk repack identik. */
        public byte[] rawTable;
        /** Byte mentah blok XML asli, supaya repack tidak mengubah encoding. */
        public byte[] rawXml;

        public String modeStr() {
            switch (mode) {
                case 0: return "Normal";
                case 1: return "NV Backup";
                case 2: return "Factory";
                default: return "Unknown(" + mode + ")";
            }
        }

        public String flashTypeStr() {
            switch (flashType) {
                case 0: return "NOR";
                case 1: return "NAND";
                case 2: return "eMMC";
                default: return "Unknown(" + flashType + ")";
            }
        }

        /** Entri yang punya data nyata (bisa diekstrak). */
        public List<Entry> dataEntries() {
            List<Entry> out = new ArrayList<>();
            for (Entry e : entries) if (e.hasData()) out.add(e);
            return out;
        }

        /** Total byte seluruh entri berdata. */
        public long totalDataSize() {
            long t = 0;
            for (Entry e : entries) if (e.hasData()) t += e.size;
            return t;
        }
    }

    // ------------------------------------------------------------------ parse

    public static Pac parse(Source src) throws IOException {
        Pac p = new Pac();
        p.source = src;
        long len = src.length();
        if (len < HDR_SIZE) throw new IOException("File terlalu kecil, bukan firmware PAC");

        byte[] h = new byte[HDR_SIZE];
        src.readFully(0, h, 0, HDR_SIZE);
        p.rawHeader = h.clone();
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);

        p.version = utf16(h, 0, 22);
        p.size = ((long) b.getInt(44) & 0xFFFFFFFFL) << 32 | (b.getInt(48) & 0xFFFFFFFFL);
        p.productName = utf16(h, 52, 256);
        p.productVersion = utf16(h, 564, 256);
        p.fileCount = b.getInt(1076);
        p.fileOffset = b.getInt(1080);
        p.mode = b.getInt(1084);
        p.flashType = b.getInt(1088);
        p.productAlias = utf16(h, 1104, 100);
        int magic = b.getInt(2116);

        if ((magic & 0xFFFFFFFFL) != PAC_MAGIC) {
            throw new IOException(String.format(Locale.US,
                    "Bukan firmware PAC Unisoc (magic 0x%08X, seharusnya 0xFFFAFFFA)",
                    magic & 0xFFFFFFFFL));
        }

        // --- tabel entri
        if (p.fileCount <= 0 || p.fileCount > 4096 || p.fileOffset <= 0
                || (long) p.fileOffset + (long) p.fileCount * ENTRY_SIZE > len) {
            throw new IOException("Tabel entri PAC tidak masuk akal (count="
                    + p.fileCount + ", offset=" + p.fileOffset + ")");
        }
        byte[] tbl = new byte[p.fileCount * ENTRY_SIZE];
        src.readFully(p.fileOffset, tbl, 0, tbl.length);
        p.rawTable = tbl.clone();
        for (int i = 0; i < p.fileCount; i++) {
            // Semua offset field bersifat absolut terhadap array, jadi tambahkan base.
            // (ByteBuffer.wrap(arr, off, len) hanya menggeser position, bukan index getInt)
            int base = i * ENTRY_SIZE;
            ByteBuffer eb = ByteBuffer.wrap(tbl).order(ByteOrder.LITTLE_ENDIAN);
            Entry en = new Entry();
            en.index = i;
            en.id = utf16(tbl, base + 4, 256);
            en.name = utf16(tbl, base + 516, 256);
            en.version = utf16(tbl, base + 1028, 256);
            en.size = ((long) eb.getInt(base + 1532) & 0xFFFFFFFFL) << 32
                    | (eb.getInt(base + 1540) & 0xFFFFFFFFL);
            en.fileFlag = eb.getInt(base + 1544);
            en.checkFlag = eb.getInt(base + 1548);
            en.dataOffset = ((long) eb.getInt(base + 1536) & 0xFFFFFFFFL) << 32
                    | (eb.getInt(base + 1552) & 0xFFFFFFFFL);
            en.omitFlag = eb.getInt(base + 1556);
            en.addrCount = eb.getInt(base + 1560);
            for (int k = 0; k < 5; k++) {
                en.addr[k] = eb.getInt(base + 1564 + k * 4) & 0xFFFFFFFFL;
            }
            // buang entri sampah: tanpa id, tanpa nama, tanpa data
            if (!en.id.isEmpty() || !en.name.isEmpty() || en.size > 0) {
                p.entries.add(en);
            }
        }

        // --- blok XML
        // PAC lama: XML tepat setelah tabel entri.
        // PAC baru: XML adalah entri sendiri dengan fileFlag == 2.
        Entry xmlEntry = null;
        for (Entry en : p.entries) {
            if (en.isXml() && en.size > 0) { xmlEntry = en; break; }
        }
        if (xmlEntry != null && xmlEntry.size < 32L * 1024 * 1024
                && xmlEntry.dataOffset + xmlEntry.size <= len) {
            byte[] xb = new byte[(int) xmlEntry.size];
            src.readFully(xmlEntry.dataOffset, xb, 0, xb.length);
            p.rawXml = xb;
            p.xml = decodeXml(xb);
            p.xmlOffset = xmlEntry.dataOffset;
            p.xmlSize = xmlEntry.size;
        } else {
            long xmlStart = (long) p.fileOffset + (long) p.fileCount * ENTRY_SIZE;
            long xmlEnd = len;
            for (Entry en : p.entries) {
                if (en.dataOffset > xmlStart && en.dataOffset < xmlEnd) xmlEnd = en.dataOffset;
            }
            if (xmlStart > 0 && xmlEnd > xmlStart && (xmlEnd - xmlStart) < 32L * 1024 * 1024) {
                byte[] xb = new byte[(int) (xmlEnd - xmlStart)];
                src.readFully(xmlStart, xb, 0, xb.length);
                p.rawXml = xb;
                p.xml = decodeXml(xb);
                p.xmlOffset = xmlStart;
                p.xmlSize = xb.length;
            }
        }

        mergeXml(p);
        return p;
    }

    // ------------------------------------------------------------------- util

    static String utf16(byte[] buf, int off, int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            int lo = buf[off + i * 2] & 0xFF;
            int hi = buf[off + i * 2 + 1] & 0xFF;
            int c = lo | (hi << 8);
            if (c == 0) break;
            sb.append((char) c);
        }
        return sb.toString().trim();
    }

    static String decodeXml(byte[] b) {
        if (b.length > 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return clean(new String(b, 2, b.length - 2, Charset.forName("UTF-16LE")));
        }
        if (b.length > 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return clean(new String(b, 2, b.length - 2, Charset.forName("UTF-16BE")));
        }
        int n = Math.min(b.length, 8192);
        int zeroOdd = 0, zeroEven = 0;
        for (int i = 0; i < n; i++) {
            if (b[i] == 0) {
                if ((i & 1) == 1) zeroOdd++; else zeroEven++;
            }
        }
        if (zeroOdd > n / 8) return clean(new String(b, Charset.forName("UTF-16LE")));
        if (zeroEven > n / 8) return clean(new String(b, Charset.forName("UTF-16BE")));
        return clean(new String(b, StandardCharsets.UTF_8));
    }

    static String clean(String s) {
        int end = s.length();
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == 0) { end = i; break; }
        }
        s = s.substring(0, end);
        int lt = s.indexOf('<');
        if (lt > 0) s = s.substring(lt);
        return s.trim();
    }

    static long parseNum(String v) {
        if (v == null) return -1;
        v = v.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.startsWith("0x")) return Long.parseLong(v.substring(2), 16);
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String attr(org.xmlpull.v1.XmlPullParser x, String name) {
        for (int i = 0; i < x.getAttributeCount(); i++) {
            String an = x.getAttributeName(i);
            if (an != null && an.toLowerCase(Locale.ROOT).equals(name)) {
                return x.getAttributeValue(i);
            }
        }
        return null;
    }

    static String human(long n) {
        if (n >= 1024L * 1024 * 1024) return String.format(Locale.US, "%.2f GB", n / 1073741824.0);
        if (n >= 1024L * 1024) return String.format(Locale.US, "%.2f MB", n / 1048576.0);
        if (n >= 1024) return String.format(Locale.US, "%.1f KB", n / 1024.0);
        return n + " B";
    }

    // -------------------------------------------------------------------- xml

    /** Gabungkan metadata XML ke entri PAC (toleran terhadap beda generasi). */
    static void mergeXml(Pac p) {
        if (p.xml == null || p.xml.isEmpty()) return;
        String xml = p.xml;
        if (xml.startsWith("<?xml")) {
            int e = xml.indexOf("?>");
            if (e > 0) xml = xml.substring(e + 2);
        }
        try {
            org.xmlpull.v1.XmlPullParserFactory fac =
                    org.xmlpull.v1.XmlPullParserFactory.newInstance();
            fac.setNamespaceAware(false);
            org.xmlpull.v1.XmlPullParser x = fac.newPullParser();
            x.setInput(new java.io.StringReader(xml));

            Entry cur = null;
            int ev = x.getEventType();
            while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    String tag = x.getName() == null ? "" : x.getName().toLowerCase(Locale.ROOT);
                    if (tag.equals("file") || tag.equals("partition") || tag.equals("image")) {
                        String id = attr(x, "id");
                        cur = findEntry(p, id);
                        if (cur == null) {
                            cur = new Entry();
                            cur.index = -1;
                            p.entries.add(cur);
                        }
                        if (id != null && !id.isEmpty() && cur.id.isEmpty()) cur.id = id;
                        String al = attr(x, "id_alias");
                        if (al == null) al = attr(x, "idalias");
                        if (al == null) al = attr(x, "id_name");
                        if (al != null && !al.isEmpty()) cur.idName = al;
                        String t = attr(x, "type");
                        if (t != null && !t.isEmpty()) cur.type = t;
                        String ba = attr(x, "base_address");
                        if (ba == null) ba = attr(x, "base");
                        if (ba != null) cur.baseAddress = parseNum(ba);
                    } else if (tag.equals("block")) {
                        if (cur != null) {
                            String ba = attr(x, "base");
                            if (ba == null) ba = attr(x, "base_address");
                            if (ba != null) {
                                long v = parseNum(ba);
                                if (v >= 0) cur.baseAddress = v;
                            }
                            String sz = attr(x, "size");
                            if (sz != null) {
                                long v = parseNum(sz);
                                if (v > 0) cur.blockSize = v;
                            }
                        }
                    }
                } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                    String tag = x.getName() == null ? "" : x.getName().toLowerCase(Locale.ROOT);
                    if (tag.equals("file") || tag.equals("partition") || tag.equals("image")) {
                        cur = null;
                    }
                }
                ev = x.next();
            }
        } catch (Exception ignored) {
            // XML rusak / tak dikenal: tetap jalan dengan data header saja
        }
    }

    static Entry findEntry(Pac p, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Entry en : p.entries) {
            if (en.id.equalsIgnoreCase(id)) return en;
        }
        for (Entry en : p.entries) {
            if (en.idName.equalsIgnoreCase(id)) return en;
        }
        return null;
    }
}
