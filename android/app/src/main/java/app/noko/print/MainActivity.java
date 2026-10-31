package app.noko.print;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String ACTION_USB = "app.noko.print.USB_PERMISSION";

    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private UsbManager usbManager;
    private UsbLink link;
    private Uri document;
    private String documentName = "";
    private Kind kind = Kind.NONE;
    private volatile boolean busy;

    private TextView fileView;
    private TextView statusView;
    private TextView copiesView;
    private TextView densityView;
    private Spinner paperSpinner;
    private CheckBox tonerBox;
    private ProgressBar progress;
    private MaterialButton printButton;
    private int copies = 1;

    private final ActivityResultLauncher<String[]> opener =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onDocument);

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB.equals(intent.getAction())) return;
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                jobs.execute(() -> attach(device));
            } else {
                say("USB permission was not granted.");
            }
        }
    };

    private enum Kind { NONE, PDF, IMAGE, TEXT }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        fileView = findViewById(R.id.file_name);
        statusView = findViewById(R.id.status);
        copiesView = findViewById(R.id.copies);
        densityView = findViewById(R.id.density_label);
        paperSpinner = findViewById(R.id.paper);
        tonerBox = findViewById(R.id.toner);
        progress = findViewById(R.id.progress);
        printButton = findViewById(R.id.print);
        SeekBar density = findViewById(R.id.density);

        paperSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Paper.values()));
        density.setProgress(2);
        density.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                densityView.setText("Toner " + (progress + 1) + " of 5");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        findViewById(R.id.pick).setOnClickListener(v -> opener.launch(new String[]{
                "application/pdf", "text/plain", "image/*"
        }));
        findViewById(R.id.test_page).setOnClickListener(v -> {
            kind = Kind.NONE;
            document = null;
            documentName = "Built-in test page";
            fileView.setText(documentName);
            say("Test page ready. Connect the printer, then print.");
        });
        findViewById(R.id.copies_minus).setOnClickListener(v -> setCopies(copies - 1));
        findViewById(R.id.copies_plus).setOnClickListener(v -> setCopies(copies + 1));
        findViewById(R.id.connect).setOnClickListener(v -> connectPrinter());
        printButton.setOnClickListener(v -> print());

        IntentFilter filter = new IntentFilter(ACTION_USB);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        say("Plug the printer in with an OTG cable. The machine code on this app is KJ73EDG.");
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        closeLink();
        jobs.shutdownNow();
        super.onDestroy();
    }

    private void setCopies(int next) {
        copies = Math.max(1, Math.min(20, next));
        copiesView.setText(String.valueOf(copies));
    }

    private void say(String line) {
        runOnUiThread(() -> statusView.setText(line));
    }

    private void onDocument(Uri uri) {
        if (uri == null) return;
        document = uri;
        documentName = nameOf(uri);
        String type = getContentResolver().getType(uri);
        if (type != null && type.equals("application/pdf")) kind = Kind.PDF;
        else if (type != null && type.startsWith("image/")) kind = Kind.IMAGE;
        else kind = Kind.TEXT;
        fileView.setText(documentName);
        say("Document ready.");
    }

    private String nameOf(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return cursor.getString(idx);
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "Document";
    }

    private void connectPrinter() {
        UsbDevice found = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (UsbLink.isM1005(device)) {
                found = device;
                break;
            }
        }
        if (found == null) {
            say("LaserJet M1005 is not on the cable yet. Check OTG and that the printer is on.");
            return;
        }
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent pending = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB), flags);
        usbManager.requestPermission(found, pending);
    }

    private void attach(UsbDevice device) {
        closeLink();
        UsbDeviceConnection connection = usbManager.openDevice(device);
        try {
            link = UsbLink.open(device, connection);
            say("Connected. " + link.describe());
        } catch (IOException e) {
            say(e.getMessage());
        }
    }

    private void closeLink() {
        if (link != null) {
            link.close();
            link = null;
        }
    }

    private void print() {
        if (busy) return;
        if (document == null && !"Built-in test page".equals(documentName)) {
            say("Choose a PDF, a photo, or the test page first.");
            return;
        }
        if (link == null) {
            say("Connect the printer first.");
            connectPrinter();
            return;
        }
        Paper paper = (Paper) paperSpinner.getSelectedItem();
        int density = ((SeekBar) findViewById(R.id.density)).getProgress() + 1;
        boolean econo = tonerBox.isChecked();
        int copyCount = copies;
        busy = true;
        progress.setVisibility(View.VISIBLE);
        printButton.setEnabled(false);
        say("Sending the page…");
        jobs.execute(() -> {
            try {
                runPrint(paper, density, econo, copyCount);
                say("Sent. The M1005 should pull a sheet now.");
            } catch (Exception e) {
                say(e.getMessage() == null ? "Print failed." : e.getMessage());
            } finally {
                busy = false;
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    printButton.setEnabled(true);
                });
            }
        });
    }

    private void runPrint(Paper paper, int density, boolean econo, int copyCount) throws IOException {
        if ("Built-in test page".equals(documentName) && document == null) {
            byte[] bits = Rasterizer.testPage(paper);
            sendPacked(bits, paper, density, econo, copyCount);
            return;
        }
        if (kind == Kind.PDF) {
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(document, "r")) {
                if (fd == null) throw new IOException("Could not open that PDF");
                PdfRenderer renderer = new PdfRenderer(fd);
                try {
                    int count = renderer.getPageCount();
                    for (int copy = 0; copy < copyCount; copy++) {
                        for (int i = 0; i < count; i++) {
                            int shown = copy * count + i + 1;
                            say("Page " + shown + " of " + (count * copyCount));
                            byte[] bits = Rasterizer.pdfPage(renderer, i, paper);
                            sendPacked(bits, paper, density, econo, 1);
                        }
                    }
                } finally {
                    renderer.close();
                }
            }
            return;
        }
        if (kind == Kind.IMAGE) {
            Bitmap bitmap = decodeImage(document);
            try {
                byte[] bits = Rasterizer.imagePage(bitmap, paper);
                sendPacked(bits, paper, density, econo, copyCount);
            } finally {
                bitmap.recycle();
            }
            return;
        }
        String text = readText(document);
        byte[] bits = Rasterizer.textPage(text, paper);
        sendPacked(bits, paper, density, econo, copyCount);
    }

    private void sendPacked(byte[] bits, Paper paper, int density, boolean econo, int copyCount) throws IOException {
        if (link == null) throw new IOException("Printer disconnected");
        byte[] job = XqxEncoder.encode(
                bits,
                paper.widthPx(),
                paper.heightPx(),
                Paper.stride(paper.widthPx()),
                Paper.DPI,
                Paper.DPI,
                paper.code,
                density,
                econo ? 1 : 0,
                copyCount);
        link.write(job);
    }

    private Bitmap decodeImage(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (bounds.outWidth / sample > 2400 || bounds.outHeight / sample > 2400) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
            if (bitmap == null) throw new IOException("That picture could not be read");
            return bitmap;
        }
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open that file");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) >= 0 && builder.length() < 20000) builder.append(buf, 0, n);
            return builder.toString();
        }
    }
}
