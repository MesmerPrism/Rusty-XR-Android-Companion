package com.cgutman.adblib;

import java.io.Closeable;
import java.io.IOException;

public interface AdbChannel extends Closeable {
    void readx(byte[] buffer, int length) throws IOException;

    void writex(AdbMessage message) throws IOException;
}
