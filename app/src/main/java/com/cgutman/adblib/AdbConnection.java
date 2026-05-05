package com.cgutman.adblib;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.util.HashMap;

public class AdbConnection implements Closeable {
    private static final long DEFAULT_CONNECT_TIMEOUT_MS = 10_000L;
    private static final long DEFAULT_OPEN_TIMEOUT_MS = 10_000L;
    AdbChannel channel;
    private int lastLocalId;
    private Thread connectionThread;
    private boolean connectAttempted;
    private boolean connected;
    private int maxData;
    private AdbCrypto crypto;
    private boolean sentSignature;
    private HashMap<Integer, AdbStream> openStreams;
    private AdbConnectionObserver observer;
    private volatile String lastConnectionThreadError;
    private volatile String lastProtocolNotice;
    private volatile boolean closing;

    private AdbConnection() {
        openStreams = new HashMap<>();
        lastLocalId = 0;
        connectionThread = createConnectionThread();
    }

    public void setObserver(AdbConnectionObserver observer) {
        this.observer = observer;
    }

    public static AdbConnection create(AdbChannel channel, AdbCrypto crypto) throws IOException {
        AdbConnection newConn = new AdbConnection();
        newConn.crypto = crypto;
        newConn.channel = channel;
        newConn.lastConnectionThreadError = null;
        newConn.lastProtocolNotice = null;
        newConn.closing = false;
        return newConn;
    }

    private Thread createConnectionThread() {
        final AdbConnection conn = this;
        return new Thread(() -> {
            AdbMessage lastMessage = null;
            while (!connectionThread.isInterrupted()) {
                try {
                    AdbMessage msg = AdbMessage.parseAdbMessage(channel);
                    lastMessage = msg;
                    String validationFailure = AdbProtocol.describeValidationFailure(msg);
                    if (validationFailure != null) {
                        String protocolNotice = describeInvalidMessage(msg, validationFailure);
                        conn.lastProtocolNotice = protocolNotice;
                        if (conn.observer != null) {
                            conn.observer.onProtocolNotice(protocolNotice);
                        }
                        if (!shouldAcceptLenientConnectMessage(msg, validationFailure)) {
                            continue;
                        }
                        String fallbackNotice = "Leniently accepting CNXN despite validation failure to support Quest USB ADB compatibility.";
                        conn.lastProtocolNotice = fallbackNotice + " " + protocolNotice;
                        if (conn.observer != null) {
                            conn.observer.onProtocolNotice(fallbackNotice);
                        }
                    } else {
                        conn.lastProtocolNotice = null;
                    }

                    if (!AdbProtocol.validateMessage(msg) && !shouldAcceptLenientConnectMessage(msg, validationFailure)) {
                        continue;
                    }

                    switch (msg.getCommand()) {
                        case AdbProtocol.CMD_OKAY:
                        case AdbProtocol.CMD_WRTE:
                        case AdbProtocol.CMD_CLSE:
                            if (!conn.connected) {
                                continue;
                            }

                            AdbStream waitingStream = openStreams.get(msg.getArg1());
                            if (waitingStream == null) {
                                continue;
                            }

                            synchronized (waitingStream) {
                                if (msg.getCommand() == AdbProtocol.CMD_OKAY) {
                                    waitingStream.updateRemoteId(msg.getArg0());
                                    waitingStream.readyForWrite();
                                    waitingStream.notify();
                                } else if (msg.getCommand() == AdbProtocol.CMD_WRTE) {
                                    waitingStream.addPayload(msg.getPayload());
                                    waitingStream.sendReady();
                                } else if (msg.getCommand() == AdbProtocol.CMD_CLSE) {
                                    conn.openStreams.remove(msg.getArg1());
                                    waitingStream.notifyClose();
                                }
                            }
                            break;

                        case AdbProtocol.CMD_AUTH:
                            if (msg.getArg0() == AdbProtocol.AUTH_TYPE_TOKEN) {
                                if (conn.observer != null) conn.observer.onAuthToken();
                                AdbMessage packet;
                                if (conn.sentSignature) {
                                    packet = AdbProtocol.generateAuth(
                                        AdbProtocol.AUTH_TYPE_RSA_PUBLIC,
                                        conn.crypto.getAdbPublicKeyPayload()
                                    );
                                    if (conn.observer != null) conn.observer.onAuthPublicKeySent();
                                } else {
                                    packet = AdbProtocol.generateAuth(
                                        AdbProtocol.AUTH_TYPE_SIGNATURE,
                                        conn.crypto.signAdbTokenPayload(msg.getPayload())
                                    );
                                    conn.sentSignature = true;
                                    if (conn.observer != null) conn.observer.onAuthSignatureSent();
                                }
                                conn.channel.writex(packet);
                            }
                            break;

                        case AdbProtocol.CMD_CNXN:
                            synchronized (conn) {
                                conn.maxData = msg.getArg1();
                                conn.connected = true;
                                conn.notifyAll();
                            }
                            if (conn.observer != null) conn.observer.onConnected(msg.getArg1());
                            break;

                        default:
                            break;
                    }
                } catch (Exception e) {
                    if (isExpectedShutdownException(e)) {
                        break;
                    }
                    conn.lastConnectionThreadError = buildConnectionThreadError(e, lastMessage);
                    if (conn.observer != null) {
                        conn.observer.onConnectionThreadError(conn.lastConnectionThreadError);
                    }
                    break;
                }
            }

            synchronized (conn) {
                cleanupStreams();
                conn.notifyAll();
                conn.connectAttempted = false;
            }
        }, "legacy-adb-connection");
    }

    private String buildConnectionThreadError(Exception exception, AdbMessage lastMessage) {
        StringBuilder builder = new StringBuilder();
        builder.append(exception.getClass().getSimpleName());
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            builder.append(": ").append(exception.getMessage());
        }
        if (lastMessage != null) {
            int payloadLength = lastMessage.getPayload() == null ? 0 : lastMessage.getPayload().length;
            builder.append(" after ");
            builder.append(describeCommand(lastMessage.getCommand()));
            builder.append("(arg0=").append(lastMessage.getArg0());
            builder.append(", arg1=").append(lastMessage.getArg1());
            builder.append(", payload=").append(payloadLength).append(")");
        }
        builder.append(" connected=").append(connected);
        builder.append(" sentSignature=").append(sentSignature);
        if (lastProtocolNotice != null && !lastProtocolNotice.isBlank()) {
            builder.append(" protocolNotice=").append(lastProtocolNotice);
        }
        builder.append(" stack=").append(trimStackTrace(exception, 6));
        return builder.toString();
    }

    private String describeInvalidMessage(AdbMessage message, String validationFailure) {
        return "Rejected "
            + describeCommand(message.getCommand())
            + "(arg0=" + message.getArg0()
            + ", arg1=" + message.getArg1()
            + ", payload=" + message.getPayloadLength()
            + "): " + validationFailure;
    }

    private boolean shouldAcceptLenientConnectMessage(AdbMessage message, String validationFailure) {
        if (validationFailure == null || message.getCommand() != AdbProtocol.CMD_CNXN) {
            return false;
        }
        if (message.getMagic() != (message.getCommand() ^ 0xFFFFFFFF)) {
            return false;
        }
        return validationFailure.startsWith("checksum mismatch");
    }

    private String trimStackTrace(Exception exception, int maxFrames) {
        StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        String[] lines = writer.toString().split("\\R");
        StringBuilder builder = new StringBuilder();
        int frames = 0;
        for (String line : lines) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(line.trim());
            if (line.trim().startsWith("at ")) {
                frames++;
                if (frames >= maxFrames) {
                    break;
                }
            }
        }
        return builder.toString();
    }

    private boolean isExpectedShutdownException(Exception exception) {
        if (!closing && !connectionThread.isInterrupted()) {
            return false;
        }
        if (!(exception instanceof IOException)) {
            return false;
        }
        String message = exception.getMessage();
        return message != null && message.equalsIgnoreCase("channel closed");
    }

    private String describeCommand(int command) {
        switch (command) {
            case AdbProtocol.CMD_CNXN:
                return "CNXN";
            case AdbProtocol.CMD_AUTH:
                return "AUTH";
            case AdbProtocol.CMD_OPEN:
                return "OPEN";
            case AdbProtocol.CMD_OKAY:
                return "OKAY";
            case AdbProtocol.CMD_CLSE:
                return "CLSE";
            case AdbProtocol.CMD_WRTE:
                return "WRTE";
            case AdbProtocol.CMD_SYNC:
                return "SYNC";
            default:
                return "0x" + Integer.toHexString(command);
        }
    }

    public int getMaxData() throws InterruptedException, IOException {
        return getMaxData(DEFAULT_CONNECT_TIMEOUT_MS);
    }

    public int getMaxData(long timeoutMs) throws InterruptedException, IOException {
        if (!connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }

        synchronized (this) {
            if (!connected) {
                wait(timeoutMs);
            }
            if (!connected) {
                throw new IOException("Connection timed out waiting for ADB handshake");
            }
        }
        return maxData;
    }

    public void connect() throws IOException, InterruptedException {
        connect(DEFAULT_CONNECT_TIMEOUT_MS);
    }

    public void connect(long timeoutMs) throws IOException, InterruptedException {
        if (connected) {
            throw new IllegalStateException("Already connected");
        }

        channel.writex(AdbProtocol.generateConnect());
        connectAttempted = true;
        lastConnectionThreadError = null;
        lastProtocolNotice = null;
        connectionThread.start();

        synchronized (this) {
            if (!connected) {
                wait(timeoutMs);
            }
            if (!connected) {
                if (observer != null) observer.onConnectionTimeout();
                String error = lastConnectionThreadError;
                String protocolNotice = lastProtocolNotice;
                if (error != null && !error.isBlank()) {
                    throw new IOException("Connection timed out waiting for ADB handshake. " + error);
                }
                if (protocolNotice != null && !protocolNotice.isBlank()) {
                    throw new IOException("Connection timed out waiting for ADB handshake. " + protocolNotice);
                }
                throw new IOException("Connection timed out waiting for ADB handshake");
            }
        }
    }

    public AdbStream open(String destination) throws UnsupportedEncodingException, IOException, InterruptedException {
        return open(destination, DEFAULT_OPEN_TIMEOUT_MS);
    }

    public AdbStream open(String destination, long timeoutMs) throws UnsupportedEncodingException, IOException, InterruptedException {
        int localId = ++lastLocalId;
        if (!connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }

        synchronized (this) {
            if (!connected) {
                wait();
            }
            if (!connected) {
                throw new IOException("Connection failed");
            }
        }

        AdbStream stream = new AdbStream(this, localId);
        openStreams.put(localId, stream);
        channel.writex(AdbProtocol.generateOpen(localId, destination));

        synchronized (stream) {
            stream.wait(timeoutMs);
        }

        if (stream.isClosed()) {
            if (observer != null) observer.onStreamOpenRejected(destination);
            throw new ConnectException("Stream open actively rejected by remote peer");
        }
        if (!stream.hasRemoteId()) {
            openStreams.remove(localId);
            if (observer != null) observer.onStreamOpenTimeout(destination);
            throw new IOException("Stream open timed out waiting for remote OKAY");
        }
        return stream;
    }

    private void cleanupStreams() {
        for (AdbStream s : openStreams.values()) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        openStreams.clear();
    }

    @Override
    public void close() throws IOException {
        if (connectionThread == null) {
            return;
        }
        closing = true;
        channel.close();
        connectionThread.interrupt();
        try {
            connectionThread.join();
        } catch (InterruptedException ignored) {
        }
    }
}
