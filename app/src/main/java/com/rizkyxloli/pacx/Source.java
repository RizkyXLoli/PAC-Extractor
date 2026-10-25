package com.rizkyxloli.pacx;

import java.io.Closeable;
import java.io.IOException;

public interface Source extends Closeable {
    long length() throws IOException;

    void readFully(long pos, byte[] buf, int off, int len) throws IOException;

    String displayName();
}
