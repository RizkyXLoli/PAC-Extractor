package com.rizkyxloli.pacx;

public final class Crc16 {
    private Crc16() { }

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
