package com.rizkyxloli.pacx;

/**
 * CRC-16/ARC (poly 0x8005 terbalik = 0xA001, init 0, tanpa final xor).
 *
 * Ini algoritma yang dipakai header PAC Unisoc untuk dua field 16-bit di akhir
 * header: wCRC1 (offset 2120) atas 2120 byte pertama, dan wCRC2 (offset 2122)
 * atas seluruh isi file mulai byte 2124. Sudah diverifikasi cocok pada firmware
 * X6725 asli (7,4 GB) — keduanya pas.
 */
public final class Crc16 {

    private Crc16() { }

    /** Hitung CRC-16/ARC lanjutan; pakai nilai kembalian sebagai init berikutnya. */
    public static int arc(byte[] d, int off, int len, int init) {
        int crc = init;
        for (int i = off; i < off + len; i++) {
            crc ^= (d[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                crc = (crc & 1) != 0 ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
            }
        }
        return crc & 0xFFFF;
    }

    public static int arc(byte[] d) {
        return arc(d, 0, d.length, 0);
    }
}
