package app.noko.print;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;

import java.io.IOException;

/** One claimed USB printer interface. Thread-safe for write/close. */
public final class UsbLink implements AutoCloseable {
    public static final int VENDOR_HP = 0x03f0;
    public static final int PRODUCT_M1005 = 0x3b17;

    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    private final UsbInterface iface;
    private final UsbEndpoint out;
    private volatile boolean open = true;
    private boolean released;

    private UsbLink(UsbDevice device, UsbDeviceConnection connection, UsbInterface iface, UsbEndpoint out) {
        this.device = device;
        this.connection = connection;
        this.iface = iface;
        this.out = out;
    }

    public static boolean isM1005(UsbDevice device) {
        return device != null
                && device.getVendorId() == VENDOR_HP
                && device.getProductId() == PRODUCT_M1005;
    }

    public static UsbLink open(UsbDevice device, UsbDeviceConnection connection) throws IOException {
        if (!isM1005(device)) {
            if (connection != null) connection.close();
            throw new IOException("That USB device is not the LaserJet M1005");
        }
        if (connection == null) {
            throw new IOException("The phone did not open the printer. Unplug and replug it.");
        }
        if (device.getConfigurationCount() > 0) {
            connection.setConfiguration(device.getConfiguration(0));
        }
        UsbInterface chosen = null;
        UsbEndpoint endpoint = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint ep = candidate.getEndpoint(e);
                boolean bulkOut = ep.getDirection() == UsbConstants.USB_DIR_OUT
                        && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK;
                if (!bulkOut) continue;
                if (chosen == null || candidate.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                    chosen = candidate;
                    endpoint = ep;
                }
            }
            if (chosen != null && chosen.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                break;
            }
        }
        if (chosen == null || endpoint == null) {
            connection.close();
            throw new IOException("The printer has no usable USB output. Replug it and try again.");
        }
        if (!connection.claimInterface(chosen, true)) {
            connection.close();
            throw new IOException("Another app is using the printer. Unplug it, wait two seconds, and retry.");
        }
        if (chosen.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
            connection.controlTransfer(0x21, 2, 0, chosen.getId(), null, 0, 2000);
        }
        return new UsbLink(device, connection, chosen, endpoint);
    }

    public UsbDevice device() { return device; }

    public String describe() {
        return "HP LaserJet M1005";
    }

    public boolean isOpen() { return open; }

    public synchronized void write(byte[] data) throws IOException {
        if (!open) throw new IOException("Printer connection is closed. Reconnect the USB cable.");
        int offset = 0;
        while (offset < data.length) {
            int length = Math.min(16384, data.length - offset);
            int sent = connection.bulkTransfer(out, data, offset, length, 30000);
            if (sent <= 0) {
                open = false;
                throw new IOException("Lost the printer while sending. Check the cable, power and paper, then print again.");
            }
            offset += sent;
        }
    }

    @Override
    public synchronized void close() {
        open = false;
        if (released) return;
        released = true;
        try {
            connection.releaseInterface(iface);
        } catch (RuntimeException ignored) {
            // already gone
        }
        try {
            connection.close();
        } catch (RuntimeException ignored) {
            // already gone
        }
    }
}
