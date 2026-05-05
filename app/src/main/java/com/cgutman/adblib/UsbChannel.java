package com.cgutman.adblib;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.IOException;

public class UsbChannel implements AdbChannel {
    private final UsbDeviceConnection deviceConnection;
    private final UsbEndpoint endpointOut;
    private final UsbEndpoint endpointIn;
    private final UsbInterface usbInterface;
    private final int defaultTimeout = 1000;
    private volatile boolean closed;

    public UsbChannel(UsbDeviceConnection connection, UsbInterface intf) {
        deviceConnection = connection;
        usbInterface = intf;

        UsbEndpoint epOut = null;
        UsbEndpoint epIn = null;
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    epOut = ep;
                } else {
                    epIn = ep;
                }
            }
        }
        if (epOut == null || epIn == null) {
            throw new IllegalArgumentException("not all endpoints found");
        }
        endpointOut = epOut;
        endpointIn = epIn;
    }

    @Override
    public void readx(byte[] buffer, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            int transferred = deviceConnection.bulkTransfer(endpointIn, buffer, offset, length - offset, defaultTimeout);
            if (transferred > 0) {
                offset += transferred;
                continue;
            }
            if (closed || Thread.currentThread().isInterrupted()) {
                throw new IOException("channel closed");
            }
            if (transferred == 0) {
                continue;
            }
            if (transferred < 0) {
                throw new IOException("bulk read fail at " + offset + "/" + length);
            }
        }
    }

    private void writex(byte[] buffer) throws IOException {
        int offset = 0;
        int transferred;
        byte[] tmp = new byte[buffer.length];
        System.arraycopy(buffer, 0, tmp, 0, buffer.length);

        while ((transferred = deviceConnection.bulkTransfer(endpointOut, tmp, buffer.length - offset, defaultTimeout)) >= 0) {
            offset += transferred;
            if (offset >= buffer.length) {
                break;
            }
            System.arraycopy(buffer, offset, tmp, 0, buffer.length - offset);
        }

        if (transferred < 0) {
            throw new IOException("bulk transfer fail");
        }
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
        closed = true;
        deviceConnection.releaseInterface(usbInterface);
        deviceConnection.close();
    }
}
