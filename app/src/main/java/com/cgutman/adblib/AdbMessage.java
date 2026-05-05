package com.cgutman.adblib;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AdbMessage {
    private ByteBuffer messageBuffer;
    private byte[] payload;

    private AdbMessage() {
    }

    public AdbMessage(int command, int arg0, int arg1, byte[] data) {
        messageBuffer = ByteBuffer.allocate(AdbProtocol.ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        messageBuffer.putInt(command);
        messageBuffer.putInt(arg0);
        messageBuffer.putInt(arg1);
        messageBuffer.putInt(data == null ? 0 : data.length);
        messageBuffer.putInt(data == null ? 0 : checksum(data));
        messageBuffer.putInt(command ^ 0xFFFFFFFF);
        payload = data;
    }

    public AdbMessage(int command, int arg0, int arg1) {
        this(command, arg0, arg1, null);
    }

    public static AdbMessage parseAdbMessage(AdbChannel in) throws IOException {
        AdbMessage msg = new AdbMessage();
        ByteBuffer packet = ByteBuffer.allocate(AdbProtocol.ADB_HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
        in.readx(packet.array(), AdbProtocol.ADB_HEADER_LENGTH);
        msg.messageBuffer = packet;

        int payloadLength = msg.getPayloadLength();
        if (payloadLength > 0) {
            msg.payload = new byte[payloadLength];
            in.readx(msg.payload, payloadLength);
        }

        return msg;
    }

    public static int checksum(byte[] payload) {
        int checksum = 0;
        for (byte b : payload) {
            checksum += (b & 0xFF);
        }
        return checksum;
    }

    public int getCommand() {
        return messageBuffer.getInt(0);
    }

    public int getArg0() {
        return messageBuffer.getInt(4);
    }

    public int getArg1() {
        return messageBuffer.getInt(8);
    }

    public int getPayloadLength() {
        return messageBuffer.getInt(12);
    }

    public int getChecksum() {
        return messageBuffer.getInt(16);
    }

    public int getMagic() {
        return messageBuffer.getInt(20);
    }

    public byte[] getPayload() {
        return payload;
    }

    public byte[] getMessage() {
        return messageBuffer.array();
    }
}
