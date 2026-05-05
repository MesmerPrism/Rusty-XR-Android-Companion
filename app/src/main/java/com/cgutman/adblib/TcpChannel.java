package com.cgutman.adblib;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class TcpChannel implements AdbChannel {
    private final Socket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    public TcpChannel(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            this.socket = socket;
            this.inputStream = socket.getInputStream();
            this.outputStream = socket.getOutputStream();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void readx(byte[] buffer, int length) throws IOException {
        int dataRead = 0;
        do {
            int bytesRead = inputStream.read(buffer, dataRead, length - dataRead);
            if (bytesRead < 0) {
                throw new IOException("Stream closed");
            }
            dataRead += bytesRead;
        } while (dataRead < length);
    }

    private void writex(byte[] buffer) throws IOException {
        outputStream.write(buffer);
        outputStream.flush();
    }

    @Override
    public void writex(AdbMessage message) throws IOException {
        writex(message.getMessage());
        if (message.getPayload() != null) {
            writex(message.getPayload());
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
