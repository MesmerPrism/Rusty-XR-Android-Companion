package com.cgutman.adblib;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

public class AdbCrypto {
    private KeyPair keyPair;
    private AdbBase64 base64;
    private String adbKeyLabel = "quest-companion@android";

    public static final int KEY_LENGTH_BITS = 2048;
    public static final int KEY_LENGTH_BYTES = KEY_LENGTH_BITS / 8;
    public static final int KEY_LENGTH_WORDS = KEY_LENGTH_BYTES / 4;

    public static final int[] SIGNATURE_PADDING_AS_INT = new int[] {
        0x00,0x01,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,
        0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0xff,0x00,
        0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,
        0x04,0x14
    };

    public static final byte[] SIGNATURE_PADDING;

    static {
        SIGNATURE_PADDING = new byte[SIGNATURE_PADDING_AS_INT.length];
        for (int i = 0; i < SIGNATURE_PADDING.length; i++) {
            SIGNATURE_PADDING[i] = (byte) SIGNATURE_PADDING_AS_INT[i];
        }
    }

    public static AdbCrypto loadAdbKeyPair(AdbBase64 base64, File privateKey, File publicKey)
        throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        AdbCrypto crypto = new AdbCrypto();

        byte[] privKeyBytes = new byte[(int) privateKey.length()];
        byte[] pubKeyBytes = new byte[(int) publicKey.length()];

        FileInputStream privIn = new FileInputStream(privateKey);
        FileInputStream pubIn = new FileInputStream(publicKey);
        privIn.read(privKeyBytes);
        pubIn.read(pubKeyBytes);
        privIn.close();
        pubIn.close();

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privKeyBytes);
        EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(pubKeyBytes);

        crypto.keyPair = new KeyPair(
            keyFactory.generatePublic(publicKeySpec),
            keyFactory.generatePrivate(privateKeySpec)
        );
        crypto.base64 = base64;
        return crypto;
    }

    public static AdbCrypto generateAdbKeyPair(AdbBase64 base64) throws NoSuchAlgorithmException {
        AdbCrypto crypto = new AdbCrypto();
        KeyPairGenerator rsaKeyPg = KeyPairGenerator.getInstance("RSA");
        rsaKeyPg.initialize(KEY_LENGTH_BITS);
        crypto.keyPair = rsaKeyPg.genKeyPair();
        crypto.base64 = base64;
        return crypto;
    }

    public byte[] signAdbTokenPayload(byte[] payload) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPrivate());
        cipher.update(SIGNATURE_PADDING);
        return cipher.doFinal(payload);
    }

    public byte[] getAdbPublicKeyPayload() throws IOException {
        byte[] convertedKey = convertRsaPublicKeyToAdbFormat((RSAPublicKey) keyPair.getPublic());
        StringBuilder keyString = new StringBuilder(720);
        keyString.append(base64.encodeToString(convertedKey));
        keyString.append(" ").append(adbKeyLabel);
        keyString.append('\0');
        return keyString.toString().getBytes("UTF-8");
    }

    public void setAdbKeyLabel(String adbKeyLabel) {
        if (adbKeyLabel == null || adbKeyLabel.isBlank()) {
            this.adbKeyLabel = "quest-companion@android";
        } else {
            this.adbKeyLabel = adbKeyLabel;
        }
    }

    public void saveAdbKeyPair(File privateKey, File publicKey) throws IOException {
        FileOutputStream privOut = new FileOutputStream(privateKey);
        FileOutputStream pubOut = new FileOutputStream(publicKey);
        privOut.write(keyPair.getPrivate().getEncoded());
        pubOut.write(keyPair.getPublic().getEncoded());
        privOut.close();
        pubOut.close();
    }

    private static byte[] convertRsaPublicKeyToAdbFormat(RSAPublicKey pubkey) {
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pubkey.getModulus();
        BigInteger r = BigInteger.ZERO.setBit(KEY_LENGTH_WORDS * 32);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger rem = n.remainder(r32);
        BigInteger n0inv = rem.modInverse(r32);

        int[] myN = new int[KEY_LENGTH_WORDS];
        int[] myRr = new int[KEY_LENGTH_WORDS];
        BigInteger[] res;
        for (int i = 0; i < KEY_LENGTH_WORDS; i++) {
            res = rr.divideAndRemainder(r32);
            rr = res[0];
            rem = res[1];
            myRr[i] = rem.intValue();

            res = n.divideAndRemainder(r32);
            n = res[0];
            rem = res[1];
            myN[i] = rem.intValue();
        }

        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(KEY_LENGTH_WORDS);
        buffer.putInt(n0inv.negate().intValue());
        for (int i : myN) {
            buffer.putInt(i);
        }
        for (int i : myRr) {
            buffer.putInt(i);
        }
        buffer.putInt(pubkey.getPublicExponent().intValue());
        return buffer.array();
    }
}
