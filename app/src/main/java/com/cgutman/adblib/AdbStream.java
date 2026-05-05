package com.cgutman.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdbStream implements Closeable {
    private final AdbConnection adbConn;
    private final int localId;
    private int remoteId;
    private final AtomicBoolean writeReady;
    private final Queue<byte[]> readQueue;
    private boolean isClosed;

    public AdbStream(AdbConnection adbConn, int localId) {
        this.adbConn = adbConn;
        this.localId = localId;
        this.readQueue = new ConcurrentLinkedQueue<>();
        this.writeReady = new AtomicBoolean(false);
        this.isClosed = false;
    }

    void addPayload(byte[] payload) {
        synchronized (readQueue) {
            readQueue.add(payload);
            readQueue.notifyAll();
        }
    }

    void sendReady() throws IOException {
        adbConn.channel.writex(AdbProtocol.generateReady(localId, remoteId));
    }

    void updateRemoteId(int remoteId) {
        this.remoteId = remoteId;
    }

    void readyForWrite() {
        writeReady.set(true);
    }

    void notifyClose() {
        isClosed = true;
        synchronized (this) {
            notifyAll();
        }
        synchronized (readQueue) {
            readQueue.notifyAll();
        }
    }

    public byte[] read() throws InterruptedException, IOException {
        return read(0L);
    }

    public byte[] read(long timeoutMs) throws InterruptedException, IOException {
        synchronized (readQueue) {
            while (true) {
                byte[] data = readQueue.poll();
                if (data != null) {
                    return data;
                }
                if (isClosed) {
                    throw new IOException("Stream closed");
                }
                if (timeoutMs > 0L) {
                    readQueue.wait(timeoutMs);
                    if (readQueue.peek() == null && !isClosed) {
                        throw new IOException("Stream read timed out waiting for remote data");
                    }
                } else {
                    readQueue.wait();
                }
            }
        }
    }

    public void write(String payload) throws IOException, InterruptedException {
        write((payload + "\0").getBytes("UTF-8"));
    }

    public void write(byte[] payload) throws IOException, InterruptedException {
        write(payload, 0L);
    }

    public void write(byte[] payload, long timeoutMs) throws IOException, InterruptedException {
        synchronized (this) {
            while (!isClosed && !writeReady.compareAndSet(true, false)) {
                if (timeoutMs > 0L) {
                    wait(timeoutMs);
                    if (!isClosed && !writeReady.get()) {
                        throw new IOException("Stream write timed out waiting for remote ready");
                    }
                } else {
                    wait();
                }
            }
            if (isClosed) {
                throw new IOException("Stream closed");
            }
        }
        adbConn.channel.writex(AdbProtocol.generateWrite(localId, remoteId, payload));
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (isClosed) {
                return;
            }
            notifyClose();
        }
        adbConn.channel.writex(AdbProtocol.generateClose(localId, remoteId));
    }

    public boolean isClosed() {
        return isClosed;
    }

    boolean hasRemoteId() {
        return remoteId != 0;
    }
}
