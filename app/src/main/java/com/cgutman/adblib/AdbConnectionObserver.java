package com.cgutman.adblib;

/**
 * Observer interface for low-level ADB protocol transitions.
 * Implementations must be thread-safe; callbacks fire from the
 * connection thread.
 */
public interface AdbConnectionObserver {
    void onAuthToken();
    void onAuthSignatureSent();
    void onAuthPublicKeySent();
    void onProtocolNotice(String message);
    void onConnected(int maxData);
    void onConnectionTimeout();
    void onConnectionThreadError(String message);
    void onStreamOpenTimeout(String destination);
    void onStreamOpenRejected(String destination);
}
