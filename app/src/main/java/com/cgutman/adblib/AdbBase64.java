package com.cgutman.adblib;

/**
 * This interface specifies the required functions for AdbCrypto to perform Base64
 * encoding of its public key.
 */
public interface AdbBase64 {
    String encodeToString(byte[] data);
}
