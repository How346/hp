package com.example.m1005print;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.example.m1005print.USB_PERMISSION";
    private static final int VENDOR_ID = 0x03F0; // 1008
    private static final int PRODUCT_ID = 0x3B17; // 15127

    private UsbManager usbManager;
    private UsbDevice printerDevice;
    private UsbDeviceConnection connection;
    private UsbInterface usbInterface;
    private UsbEndpoint bulkOutEndpoint;

    private TextView statusText;
    private Button printButton;
    private Uri selectedPdfUri;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            openPrinter(device);
                        }
                    } else {
                        updateStatus("USB permission denied");
                    }
                }
            }
        }
    };

    private final ActivityResultLauncher<String> pdfPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    selectedPdfUri = uri;
                    updateStatus("PDF selected: " + uri.getLastPathSegment());
                    printButton.setEnabled(connection != null);
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        Button connectButton = findViewById(R.id.connectButton);
        Button pickPdfButton = findViewById(R.id.pickPdfButton);
        printButton = findViewById(R.id.printButton);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        connectButton.setOnClickListener(v -> findAndConnectPrinter());
        pickPdfButton.setOnClickListener(v -> pdfPicker.launch("application/pdf"));
        printButton.setOnClickListener(v -> printPdf());

        // Check if already attached
        findAndConnectPrinter();
    }

    private void findAndConnectPrinter() {
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        for (UsbDevice device : deviceList.values()) {
            if (device.getVendorId() == VENDOR_ID && device.getProductId() == PRODUCT_ID) {
                printerDevice = device;
                requestPermission(device);
                return;
            }
        }
        updateStatus("HP LaserJet M1005 not found. Connect via OTG and try again.");
    }

    private void requestPermission(UsbDevice device) {
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
        usbManager.requestPermission(device, permissionIntent);
        updateStatus("Requesting USB permission...");
    }

    private void openPrinter(UsbDevice device) {
        connection = usbManager.openDevice(device);
        if (connection == null) {
            updateStatus("Failed to open USB device");
            return;
        }

        // Usually interface 0 is the printer interface for these devices
        if (device.getInterfaceCount() > 0) {
            usbInterface = device.getInterface(0);
            if (!connection.claimInterface(usbInterface, true)) {
                updateStatus("Failed to claim interface");
                return;
            }

            // Find bulk OUT endpoint
            for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
                UsbEndpoint ep = usbInterface.getEndpoint(i);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    bulkOutEndpoint = ep;
                    break;
                }
            }

            if (bulkOutEndpoint != null) {
                updateStatus("Connected to HP LaserJet M1005! Ready.");
                if (selectedPdfUri != null) printButton.setEnabled(true);
            } else {
                updateStatus("No bulk OUT endpoint found");
            }
        } else {
            updateStatus("No interfaces on device");
        }
    }

    private void printPdf() {
        if (connection == null || bulkOutEndpoint == null || selectedPdfUri == null) {
            Toast.makeText(this, "Not ready to print", Toast.LENGTH_SHORT).show();
            return;
        }

        updateStatus("Preparing print job... (implement protocol here)");

        // TODO: Implement host-based protocol for M1005.
        // Typical steps:
        // 1. Use PdfRenderer to render pages to Bitmap (A4 @ 600dpi or 300dpi)
        // 2. Convert Bitmap to monochrome 1-bit raster
        // 3. Wrap in the proprietary HP host-based command sequence that the printer expects
        // 4. Send via bulk transfer: connection.bulkTransfer(bulkOutEndpoint, data, data.length, timeout)

        // Example skeleton for sending raw bytes (replace with real protocol data):
        try {
            // Dummy test data - will likely not print usefully, but shows the path
            byte[] testData = "\u001B%-12345X@PJL\r\n@PJL ENTER LANGUAGE=PCL\r\n\u001BEThis is a test page from Android OTG\r\n\f\u001B%-12345X".getBytes("ISO-8859-1");
            int result = connection.bulkTransfer(bulkOutEndpoint, testData, testData.length, 5000);
            if (result >= 0) {
                updateStatus("Sent " + result + " bytes. Check printer. For real printing, implement M1005 host-based protocol.");
            } else {
                updateStatus("Bulk transfer failed: " + result);
            }
        } catch (Exception e) {
            updateStatus("Error: " + e.getMessage());
        }
    }

    private void updateStatus(String msg) {
        runOnUiThread(() -> statusText.setText("Status: " + msg));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
        if (connection != null) {
            if (usbInterface != null) connection.releaseInterface(usbInterface);
            connection.close();
        }
    }
}
