package com.cgutman.adblib;

import java.io.UnsupportedEncodingException;

public class AdbProtocol {
    public static final int A_SYNC = 0x434E5953;
    public static final int A_CNXN = 0x4E584E43;
    public static final int A_OPEN = 0x4E45504F;
    public static final int A_OKAY = 0x59414B4F;
    public static final int A_CLSE = 0x45534C43;
    public static final int A_WRTE = 0x45545257;
    public static final int A_AUTH = 0x48545541;

    public static final int CMD_SYNC = A_SYNC;
    public static final int CMD_CNXN = A_CNXN;
    public static final int CMD_OPEN = A_OPEN;
    public static final int CMD_OKAY = A_OKAY;
    public static final int CMD_CLSE = A_CLSE;
    public static final int CMD_WRTE = A_WRTE;
    public static final int CMD_AUTH = A_AUTH;

    public static final int A_VERSION = 0x01000000;
    public static final int A_MAXDATA = 4096;

    public static final int AUTH_TYPE_TOKEN = 1;
    public static final int AUTH_TYPE_SIGNATURE = 2;
    public static final int AUTH_TYPE_RSA_PUBLIC = 3;

    public static final int ADB_HEADER_LENGTH = 24;

    public static boolean validateMessage(AdbMessage msg) {
        return describeValidationFailure(msg) == null;
    }

    public static String describeValidationFailure(AdbMessage msg) {
        if (msg.getMagic() != (msg.getCommand() ^ 0xFFFFFFFF)) {
            return "magic mismatch";
        }

        switch (msg.getCommand()) {
            case CMD_CNXN:
            case CMD_WRTE:
            case CMD_AUTH:
                if (msg.getPayload() == null) {
                    return "payload missing";
                }
                int checksum = AdbMessage.checksum(msg.getPayload());
                if (checksum != msg.getChecksum()) {
                    return "checksum mismatch expected=" + checksum + " actual=" + msg.getChecksum();
                }
                return null;
            default:
                if (msg.getPayloadLength() != 0) {
                    return "unexpected payload length=" + msg.getPayloadLength();
                }
                return null;
        }
    }

    public static AdbMessage generateMessage(int command, int arg0, int arg1, byte[] payload) {
        return new AdbMessage(command, arg0, arg1, payload);
    }

    public static AdbMessage generateConnect() {
        return generateMessage(CMD_CNXN, A_VERSION, A_MAXDATA, "host::\0".getBytes());
    }

    public static AdbMessage generateAuth(int type, byte[] data) {
        return generateMessage(CMD_AUTH, type, 0, data);
    }

    public static AdbMessage generateOpen(int localId, String destination) throws UnsupportedEncodingException {
        return generateMessage(CMD_OPEN, localId, 0, (destination + "\0").getBytes("UTF-8"));
    }

    public static AdbMessage generateWrite(int localId, int remoteId, byte[] data) {
        return generateMessage(CMD_WRTE, localId, remoteId, data);
    }

    public static AdbMessage generateClose(int localId, int remoteId) {
        return generateMessage(CMD_CLSE, localId, remoteId, null);
    }

    public static AdbMessage generateReady(int localId, int remoteId) {
        return generateMessage(CMD_OKAY, localId, remoteId, null);
    }
}
