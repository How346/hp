package app.noko.print;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Matrix;
import android.graphics.RectF;
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
import android.widget.ImageView;
import android.widget.EditText;
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
    private volatile boolean connecting;
    private boolean landscape;
    private boolean receiverRegistered;
    private int documentPages = 1;
    private int previewGeneration;

    private TextView fileView;
    private TextView statusView;
    private TextView copiesView;
    private TextView densityView;
    private Spinner paperSpinner;
    private Spinner scaleSpinner;
    private Spinner marginSpinner;
    private Spinner positionSpinner;
    private CheckBox collateBox;
    private CheckBox tonerBox;
    private ProgressBar progress;
    private MaterialButton printButton;
    private ImageView previewView;
    private EditText pageFrom;
    private EditText pageTo;
    private TextView connectionState;
    private int copies = 1;

    private final ActivityResultLauncher<String[]> opener =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onDocument);

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice detached;
                if (Build.VERSION.SDK_INT >= 33) detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
                else detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (detached != null && link != null && detached.getDeviceId() == linkDeviceId()) {
                    closeLink();
                    connecting = false;
                    setConnectionState(false, "Printer unplugged. Reconnect when ready.");
                }
                return;
            }
            if (!ACTION_USB.equals(intent.getAction())) return;
            connecting = false;
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
                setConnectionState(false, "USB permission was not granted. Tap Connect and allow access.");
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
        previewView = findViewById(R.id.preview);
        pageFrom = findViewById(R.id.page_from);
        pageTo = findViewById(R.id.page_to);
        connectionState = findViewById(R.id.connection_state);
        statusView = findViewById(R.id.status);
        copiesView = findViewById(R.id.copies);
        densityView = findViewById(R.id.density_label);
        paperSpinner = findViewById(R.id.paper);
        tonerBox = findViewById(R.id.toner);
        collateBox = findViewById(R.id.collate);
        scaleSpinner = findViewById(R.id.scale_mode);
        marginSpinner = findViewById(R.id.margins);
        positionSpinner = findViewById(R.id.position);
        scaleSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Fit to page", "Fill page (crop edges)", "Fit width"}));
        marginSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"No margins", "Narrow", "Normal", "Wide"}));
        positionSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Center", "Top", "Bottom"}));
        progress = findViewById(R.id.progress);
        printButton = findViewById(R.id.print);
        SeekBar density = findViewById(R.id.density);

        paperSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Paper.values()));
        Spinner orientationSpinner = findViewById(R.id.orientation);
        orientationSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Portrait", "Landscape"}));
        orientationSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                landscape = position == 1;
                refreshPreview();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        paperSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { refreshPreview(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        android.widget.AdapterView.OnItemSelectedListener previewSettingsListener =
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { refreshPreview(); }
                    @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                };
        scaleSpinner.setOnItemSelectedListener(previewSettingsListener);
        marginSpinner.setOnItemSelectedListener(previewSettingsListener);
        positionSpinner.setOnItemSelectedListener(previewSettingsListener);
        density.setProgress(2);
        density.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                densityView.setText("Print darkness · " + (progress + 1) + " of 5");
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
            documentPages = 1;
            pageFrom.setText("1"); pageTo.setText("1");
            fileView.setText(documentName);
            refreshPreview();
            say("Test page ready. Connect the printer, then print.");
        });
        findViewById(R.id.copies_minus).setOnClickListener(v -> setCopies(copies - 1));
        findViewById(R.id.copies_plus).setOnClickListener(v -> setCopies(copies + 1));
        findViewById(R.id.connect).setOnClickListener(v -> connectPrinter());
        printButton.setOnClickListener(v -> print());

        IntentFilter filter = new IntentFilter(ACTION_USB);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, filter);
        receiverRegistered = true;
        refreshPreview();
        say("Plug the printer in with an OTG cable. The machine code on this app is KJ73EDG.");
    }

    @Override
    protected void onDestroy() {
        if (receiverRegistered) unregisterReceiver(usbReceiver);
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
        String lowerName = documentName == null ? "" : documentName.toLowerCase(java.util.Locale.ROOT);
        if ((type != null && type.equalsIgnoreCase("application/pdf")) || lowerName.endsWith(".pdf")) {
            kind = Kind.PDF;
        } else if ((type != null && type.startsWith("image/")) || isImageName(lowerName)) {
            kind = Kind.IMAGE;
        } else if ((type != null && type.startsWith("text/")) || isTextName(lowerName)) {
            kind = Kind.TEXT;
        } else {
            kind = Kind.NONE;
            document = null;
            say("Unsupported file. Choose a PDF, JPG, PNG, or TXT file.");
            return;
        }
        fileView.setText(documentName);
        documentPages = 1;
        if (kind == Kind.PDF) {
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
                if (fd != null) documentPages = Rasterizer.pdfPageCount(fd);
            } catch (Exception e) { say("Could not read PDF page count: " + e.getMessage()); }
        }
        pageFrom.setText("1"); pageTo.setText(String.valueOf(documentPages));
        refreshPreview();
        say(documentPages + " page" + (documentPages == 1 ? "" : "s") + " ready.");
    }

    private boolean isImageName(String name) {
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
                || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".gif");
    }

    private boolean isTextName(String name) {
        return name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".log")
                || name.endsWith(".md");
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
        if (connecting || (link != null && link.isOpen())) {
            if (link != null && link.isOpen()) setConnectionState(true, "Printer is connected and ready.");
            return;
        }
        connecting = true;
        setConnectionState(false, "Looking for HP LaserJet M1005…");
        UsbDevice found = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (UsbLink.isM1005(device)) {
                found = device;
                break;
            }
        }
        if (found == null) {
            connecting = false;
            setConnectionState(false, "Printer not found. Check OTG and power.");
            return;
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        // Android 14+ blocks mutable PendingIntents with implicit intents.
        // Setting our package keeps the USB permission callback explicit while
        // still allowing UsbManager to attach its permission/device extras.
        Intent permissionIntent = new Intent(ACTION_USB).setPackage(getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(this, 1001, permissionIntent, flags);
        usbManager.requestPermission(found, pending);
    }

    private int linkDeviceId() { return link == null ? -1 : link.deviceId(); }

    private volatile String lastConnectionLabel = "";
    private void setConnectionState(boolean connected, String message) {
        final String label = (connected ? "Connected · " : "Not connected · ") + "USB OTG";
        runOnUiThread(() -> {
            if (!label.equals(lastConnectionLabel)) {
                connectionState.setText(label);
                lastConnectionLabel = label;
            }
            say(message);
        });
    }

    private void attach(UsbDevice device) {
        if (link != null && link.isOpen() && link.deviceId() == device.getDeviceId()) {
            connecting = false;
            setConnectionState(true, "Printer connection is stable.");
            return;
        }
        if (link != null && link.isOpen() && link.deviceId() != device.getDeviceId()) {
            closeLink();
        }
        if (link != null && link.isOpen()) {
            connecting = false;
            setConnectionState(true, "Printer connection is ready.");
            return;
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        try {
            link = UsbLink.open(device, connection);
            connecting = false;
            setConnectionState(true, "Connected. " + link.describe());
        } catch (Exception e) {
            connecting = false;
            if (connection != null) {
                try { connection.close(); } catch (RuntimeException ignored) {}
            }
            setConnectionState(false, e.getMessage() == null ? "Could not connect to printer." : e.getMessage());
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
        if (link == null || !link.isOpen()) {
            say("Connect the printer first.");
            connectPrinter();
            return;
        }
        Paper paper = (Paper) paperSpinner.getSelectedItem();
        int from = parsePage(pageFrom, 1);
        int to = parsePage(pageTo, documentPages);
        if (kind == Kind.PDF && (from < 1 || to < from || to > documentPages)) {
            say("Enter a valid page range from 1 to " + documentPages + ".");
            return;
        }
        int density = ((SeekBar) findViewById(R.id.density)).getProgress() + 1;
        boolean econo = tonerBox.isChecked();
        int copyCount = copies;
        int marginMode = marginSpinner.getSelectedItemPosition();
        int scaleMode = scaleSpinner.getSelectedItemPosition();
        int positionMode = positionSpinner.getSelectedItemPosition();
        boolean collate = collateBox.isChecked();
        busy = true;
        progress.setVisibility(View.VISIBLE);
        printButton.setEnabled(false);
        say("Sending the page…");
        jobs.execute(() -> {
            try {
                runPrint(paper, density, econo, copyCount, landscape, from, to,
                        marginMode, scaleMode, positionMode, collate);
                say("Sent. The M1005 should pull a sheet now.");
            } catch (Exception e) {
                if (link == null || !link.isOpen()) setConnectionState(false, e.getMessage() == null ? "Printer connection lost. Reconnect and retry." : e.getMessage());
                else say(e.getMessage() == null ? "Print failed." : e.getMessage());
            } finally {
                busy = false;
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    printButton.setEnabled(true);
                });
            }
        });
    }

    private void runPrint(Paper paper, int density, boolean econo, int copyCount, boolean landscape,
                          int fromPage, int toPage, int marginMode, int scaleMode,
                          int positionMode, boolean collate) throws IOException {
        if ("Built-in test page".equals(documentName) && document == null) {
            byte[] bits = Rasterizer.testPage(paper, landscape);
            sendPacked(bits, paper, density, econo, copyCount, landscape);
            return;
        }
        if (kind == Kind.PDF) {
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(document, "r")) {
                if (fd == null) throw new IOException("Could not open that PDF");
                PdfRenderer renderer = new PdfRenderer(fd);
                try {
                    int count = renderer.getPageCount();
                    int first = Math.max(0, fromPage - 1);
                    int last = Math.min(count - 1, toPage - 1);
                    int selectedCount = last - first + 1;
                    int total = selectedCount * copyCount;
                    int sent = 0;
                    if (collate) {
                        for (int copy = 0; copy < copyCount; copy++) {
                            for (int i = first; i <= last; i++) {
                                say("Printing sheet " + (++sent) + " of " + total);
                                byte[] bits = Rasterizer.pdfPage(renderer, i, paper, landscape,
                                        marginMode, scaleMode, positionMode);
                                sendPacked(bits, paper, density, econo, 1, landscape);
                            }
                        }
                    } else {
                        for (int i = first; i <= last; i++) {
                            byte[] bits = Rasterizer.pdfPage(renderer, i, paper, landscape,
                                    marginMode, scaleMode, positionMode);
                            for (int copy = 0; copy < copyCount; copy++) {
                                say("Printing sheet " + (++sent) + " of " + total);
                                sendPacked(bits, paper, density, econo, 1, landscape);
                            }
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
                byte[] bits = Rasterizer.imagePage(bitmap, paper, landscape,
                        marginMode, scaleMode, positionMode);
                sendPacked(bits, paper, density, econo, copyCount, landscape);
            } finally {
                bitmap.recycle();
            }
            return;
        }
        String text = readText(document);
        byte[] bits = Rasterizer.textPage(text, paper, landscape);
        sendPacked(bits, paper, density, econo, copyCount, landscape);
    }

    private void sendPacked(byte[] bits, Paper paper, int density, boolean econo, int copyCount, boolean landscape) throws IOException {
        if (link == null) throw new IOException("Printer disconnected");
        byte[] job = XqxEncoder.encode(
                bits,
                paper.widthPx(landscape),
                paper.heightPx(landscape),
                Paper.stride(paper.widthPx(landscape)),
                Paper.DPI,
                Paper.DPI,
                paper.code,
                density,
                econo ? 1 : 0,
                copyCount);
        link.write(job);
    }

    private int parsePage(EditText field, int fallback) {
        try { return Integer.parseInt(field.getText().toString().trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private void refreshPreview() {
        if (previewView == null) return;
        final int generation = ++previewGeneration;
        final Paper selectedPaper = (Paper) paperSpinner.getSelectedItem();
        final Paper previewPaper = selectedPaper == null ? Paper.A4 : selectedPaper;
        final boolean previewLandscape = landscape;
        final Uri previewDocument = document;
        final Kind previewKind = kind;
        jobs.execute(() -> {
            Bitmap bitmap = null;
            try {
                int w = 560;
                double ratio = previewLandscape
                        ? previewPaper.widthMm / previewPaper.heightMm
                        : previewPaper.heightMm / previewPaper.widthMm;
                int h = Math.max(300, (int) Math.round(w * ratio));
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
                Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
                border.setColor(Color.BLACK); border.setStyle(Paint.Style.STROKE); border.setStrokeWidth(2f);
                canvas.drawRect(8, 8, w - 8, h - 8, border);
                if (previewDocument != null && previewKind == Kind.IMAGE) {
                    Bitmap source = decodeImage(previewDocument);
                    try {
                        float margin = 22f;
                        float scale = Math.min((w - margin * 2) / source.getWidth(), (h - margin * 2) / source.getHeight());
                        float dw = source.getWidth() * scale, dh = source.getHeight() * scale;
                        Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                        canvas.drawBitmap(source, null, new RectF((w-dw)/2, (h-dh)/2, (w+dw)/2, (h+dh)/2), imagePaint);
                    } finally { source.recycle(); }
                } else if (previewDocument != null && previewKind == Kind.PDF) {
                    try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(previewDocument, "r")) {
                        if (fd != null) {
                            PdfRenderer renderer = new PdfRenderer(fd);
                            try {
                                PdfRenderer.Page page = renderer.openPage(0);
                                try {
                                    Bitmap pageBitmap = Bitmap.createBitmap(w - 44, h - 44, Bitmap.Config.ARGB_8888);
                                    pageBitmap.eraseColor(Color.WHITE);
                                    float scale = Math.min((w - 44f) / page.getWidth(), (h - 44f) / page.getHeight());
                                    Matrix matrix = new Matrix(); matrix.setScale(scale, scale);
                                    page.render(pageBitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                                    canvas.drawBitmap(pageBitmap, (w-pageBitmap.getWidth())/2f, (h-pageBitmap.getHeight())/2f, null);
                                    pageBitmap.recycle();
                                } finally { page.close(); }
                            } finally { renderer.close(); }
                        }
                    }
                } else {
                    Paint text = new Paint(Paint.ANTI_ALIAS_FLAG); text.setColor(Color.BLACK); text.setTextSize(22f); text.setFakeBoldText(true);
                    canvas.drawText("Noko Print", 34, 82, text);
                    text.setTextSize(13f); text.setFakeBoldText(false);
                    canvas.drawText("HP LaserJet M1005", 34, 112, text);
                    canvas.drawText(previewPaper.label + " · 600 DPI", 34, 136, text);
                    canvas.drawLine(34, 158, w - 34, 158, text);
                    canvas.drawText("Black and white print preview", 34, 190, text);
                }
                int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
                bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                for (int i = 0; i < pixels.length; i++) {
                    int c = pixels[i];
                    int lum = (((c >> 16) & 255) * 54 + ((c >> 8) & 255) * 183 + (c & 255) * 19) >> 8;
                    pixels[i] = Color.rgb(lum, lum, lum);
                }
                bitmap.setPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
                Bitmap ready = bitmap;
                runOnUiThread(() -> {
                    if (!isFinishing() && generation == previewGeneration) previewView.setImageBitmap(ready);
                    else ready.recycle();
                });
            } catch (Exception ignored) {
                if (bitmap != null) bitmap.recycle();
            }
        });
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
