package app.noko.print;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

public class MainActivity extends AppCompatActivity {
    private static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService previewExec = Executors.newSingleThreadExecutor();
    private final ExecutorService printExec = Executors.newSingleThreadExecutor();
    private final ExecutorService loadExec = Executors.newSingleThreadExecutor();

    private PrintSettings s;
    private PrinterConnection conn;
    private RecentStore recents;
    private Document doc;
    private int sheetIndex;
    private int sheetTotal = 1;
    private int pageTotal = 0;
    private int previewGen;
    private volatile boolean busy;
    private volatile boolean cancelled;
    private boolean settingsBuilt;
    private BottomSheetDialog settingsDialog;
    private View settingsContent;
    private PowerManager.WakeLock wakeLock;
    private boolean previewOk = true;

    private View home;
    private View editor;
    private TextView editorTitle;
    private ImageView preview;
    private ProgressBar previewSpinner;
    private TextView previewMessage;
    private TextView sheetLabel;
    private TextView summary;
    private TextView copiesView;
    private MaterialButton toneButton;
    private MaterialButton printButton;
    private View jobPanel;
    private TextView jobText;
    private ProgressBar jobProgress;
    private LinearLayout recentList;
    private TextView recentEmpty;
    private Bitmap shownBitmap;

    private final ActivityResultLauncher<String[]> pickDocument =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) loadUris(Collections.singletonList(uri), true);
            });

    private final ActivityResultLauncher<String[]> pickPhotos =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
                if (uris != null && !uris.isEmpty()) loadUris(new ArrayList<>(uris), true);
            });

    private final Runnable previewTask = this::refreshPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!night);
        bars.setAppearanceLightNavigationBars(!night);

        s = PrintSettings.load(this);
        recents = new RecentStore(this);

        home = findViewById(R.id.home);
        editor = findViewById(R.id.editor);
        editorTitle = findViewById(R.id.editor_title);
        preview = findViewById(R.id.preview);
        previewSpinner = findViewById(R.id.preview_spinner);
        previewMessage = findViewById(R.id.preview_message);
        sheetLabel = findViewById(R.id.sheet_label);
        summary = findViewById(R.id.summary);
        copiesView = findViewById(R.id.copies);
        toneButton = findViewById(R.id.btn_tone);
        printButton = findViewById(R.id.btn_print);
        jobPanel = findViewById(R.id.job_panel);
        jobText = findViewById(R.id.job_text);
        jobProgress = findViewById(R.id.job_progress);
        recentList = findViewById(R.id.recent_list);
        recentEmpty = findViewById(R.id.recent_empty);

        conn = new PrinterConnection(this, this::onConnectionState);

        findViewById(R.id.tile_docs).setOnClickListener(v -> pickDocument.launch(new String[]{
                "application/pdf", "text/*", DOCX_MIME, "image/*"}));
        findViewById(R.id.tile_photos).setOnClickListener(v -> pickPhotos.launch(new String[]{"image/*"}));
        findViewById(R.id.tile_text).setOnClickListener(v -> askForText());
        findViewById(R.id.tile_test).setOnClickListener(v ->
                openDocument(new Document(Document.Kind.TEST, "Test page", null, null, 1)));
        findViewById(R.id.conn_action).setOnClickListener(v -> conn.connectNow());
        findViewById(R.id.conn_row).setOnClickListener(v -> conn.connectNow());

        findViewById(R.id.btn_back).setOnClickListener(v -> showHome());
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());
        findViewById(R.id.btn_settings_top).setOnClickListener(v -> showSettings());
        findViewById(R.id.btn_prev).setOnClickListener(v -> moveSheet(-1));
        findViewById(R.id.btn_next).setOnClickListener(v -> moveSheet(1));
        findViewById(R.id.copies_minus).setOnClickListener(v -> setCopies(s.copies - 1));
        findViewById(R.id.copies_plus).setOnClickListener(v -> setCopies(s.copies + 1));
        findViewById(R.id.btn_cancel).setOnClickListener(v -> {
            cancelled = true;
            jobText.setText("Cancelling…");
        });
        printButton.setOnClickListener(v -> onPrint());
        toneButton.setOnClickListener(v -> {
            s.grayPreview = !s.grayPreview;
            s.save(this);
            updateToneButton();
            schedulePreview();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (editor.getVisibility() == View.VISIBLE) {
                    showHome();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        setupPreviewGestures();
        copiesView.setText(String.valueOf(s.copies));
        updateToneButton();
        applyLayoutOrientation();
        refreshRecents();
        onConnectionState(conn.state(), conn.message());
        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        conn.start();
    }

    @Override
    protected void onStop() {
        conn.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        releaseWake();
        conn.release();
        previewExec.shutdownNow();
        printExec.shutdownNow();
        loadExec.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLayoutOrientation();
    }

    private void setupPreviewGestures() {
        final GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (a == null || b == null) return false;
                float dx = b.getX() - a.getX();
                float dy = b.getY() - a.getY();
                if (Math.abs(dx) > dp(60) && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                    moveSheet(dx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        preview.setOnTouchListener((v, e) -> {
            gd.onTouchEvent(e);
            return true;
        });
    }

    // ------------------------------------------------------------------ layout

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void applyLayoutOrientation() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body = findViewById(R.id.editor_body);
        View previewPanel = findViewById(R.id.preview_panel);
        View actionPanel = findViewById(R.id.action_panel);
        LinearLayout.LayoutParams pl;
        LinearLayout.LayoutParams al;
        if (land) {
            body.setOrientation(LinearLayout.HORIZONTAL);
            pl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            al = new LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            body.setOrientation(LinearLayout.VERTICAL);
            pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            al = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        previewPanel.setLayoutParams(pl);
        actionPanel.setLayoutParams(al);
    }

    private void showHome() {
        if (busy) {
            snack("A print job is running. Cancel it first.");
            return;
        }
        editor.setVisibility(View.GONE);
        home.setAlpha(0f);
        home.setVisibility(View.VISIBLE);
        home.animate().alpha(1f).setDuration(180).start();
        refreshRecents();
    }

    private void showEditor() {
        if (editor.getVisibility() == View.VISIBLE) return;
        home.setVisibility(View.GONE);
        editor.setAlpha(0f);
        editor.setVisibility(View.VISIBLE);
        editor.animate().alpha(1f).setDuration(180).start();
    }

    private void snack(String text) {
        Snackbar.make(findViewById(R.id.root), text, Snackbar.LENGTH_LONG).show();
    }

    // -------------------------------------------------------------- connection

    private void tint(View dot, int colorRes) {
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }

    private void onConnectionState(PrinterConnection.State st, String msg) {
        TextView title = findViewById(R.id.conn_title);
        TextView detail = findViewById(R.id.conn_detail);
        MaterialButton action = findViewById(R.id.conn_action);
        TextView rowText = findViewById(R.id.conn_text2);
        View dot = findViewById(R.id.conn_dot);
        View dot2 = findViewById(R.id.conn_dot2);
        int color;
        String t;
        String row;
        String button = null;
        switch (st) {
            case CONNECTED:
                color = R.color.ok;
                t = "Printer connected";
                row = "HP M1005 connected";
                break;
            case CONNECTING:
                color = R.color.warn;
                t = "Connecting…";
                row = "Connecting to printer…";
                break;
            case PERMISSION:
                color = R.color.warn;
                t = "Allow USB access";
                row = "Waiting for USB permission";
                button = "Allow";
                break;
            case ERROR:
                color = R.color.err;
                t = "Connection problem";
                row = "Printer problem · tap to retry";
                button = "Retry";
                break;
            default:
                color = R.color.idle;
                t = "No printer connected";
                row = "Printer not connected · tap to connect";
                button = "Connect";
        }
        MaterialCardView card = findViewById(R.id.conn_card);
        card.setStrokeColor(ContextCompat.getColor(this, st == PrinterConnection.State.CONNECTED ? R.color.ok
                : st == PrinterConnection.State.ERROR ? R.color.err : R.color.line));
        title.setText(t);
        detail.setText(msg);
        detail.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
        rowText.setText(row);
        tint(dot, color);
        tint(dot2, color);
        if (button == null) {
            action.setVisibility(View.GONE);
        } else {
            action.setText(button);
            action.setVisibility(View.VISIBLE);
        }
    }

    // --------------------------------------------------------------- documents

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
            conn.scanSoon();
            return;
        }
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            loadUris(Collections.singletonList(intent.getData()), false);
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (stream != null) {
                loadUris(Collections.singletonList(stream), false);
            } else if (text != null && !text.isEmpty()) {
                openDocument(new Document(Document.Kind.TEXT, "Shared text", null, text, 0));
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri.class);
            if (list != null && !list.isEmpty()) loadUris(list, false);
        }
    }

    private void askForText() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(6);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setHint("Type or paste what you want to print");
        FrameLayoutPad wrap = new FrameLayoutPad(this, input, dp(20));
        new MaterialAlertDialogBuilder(this)
                .setTitle("Type or paste")
                .setView(wrap)
                .setPositiveButton("Preview", (d, w) -> {
                    String text = input.getText().toString();
                    if (text.trim().isEmpty()) {
                        snack("Nothing to print yet.");
                    } else {
                        openDocument(new Document(Document.Kind.TEXT, "Typed text", null, text, 0));
                    }
                })
                .setNeutralButton("Paste", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = cm == null ? null : cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).coerceToText(this);
                        if (text != null && text.toString().trim().length() > 0) {
                            openDocument(new Document(Document.Kind.TEXT, "Pasted text", null, text.toString(), 0));
                            return;
                        }
                    }
                    snack("The clipboard is empty.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Small helper that pads a view inside a dialog. */
    private static final class FrameLayoutPad extends android.widget.FrameLayout {
        FrameLayoutPad(Context ctx, View child, int pad) {
            super(ctx);
            setPadding(pad, pad / 2, pad, 0);
            addView(child, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void loadUris(List<Uri> uris, boolean persist) {
        if (uris.isEmpty()) return;
        final List<Uri> picked = new ArrayList<>(uris);
        if (persist) {
            for (Uri u : picked) {
                try {
                    getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException | IllegalArgumentException ignored) {
                    // not every provider offers persistable access
                }
            }
        }
        snack("Opening…");
        loadExec.execute(() -> {
            String error = null;
            Document result = null;
            Uri first = picked.get(0);
            try {
                String firstName = Document.nameOf(getContentResolver(), first);
                Document.Kind kind = Document.detect(getContentResolver(), first, firstName);
                if (kind == null) {
                    error = "That file type isn't supported. Choose a PDF, Word, text or picture file.";
                } else if (kind == Document.Kind.PDF) {
                    int pages = countPdf(first);
                    result = new Document(kind, firstName, Collections.singletonList(first), null, pages);
                } else if (kind == Document.Kind.IMAGES) {
                    List<Uri> images = new ArrayList<>();
                    for (Uri u : picked) {
                        String n = Document.nameOf(getContentResolver(), u);
                        if (Document.detect(getContentResolver(), u, n) == Document.Kind.IMAGES) images.add(u);
                    }
                    String label = images.size() > 1 ? firstName + " + " + (images.size() - 1) + " more" : firstName;
                    result = new Document(kind, label, images, null, images.size());
                } else {
                    String type = getContentResolver().getType(first);
                    String lower = firstName.toLowerCase(Locale.ROOT);
                    String text = Document.isDocx(type, lower) ? readDocx(first) : readText(first);
                    result = new Document(kind, firstName, Collections.singletonList(first), text, 0);
                }
            } catch (SecurityException e) {
                error = "Access to that file has expired. Pick it again from Documents.";
            } catch (IOException e) {
                error = e.getMessage() == null ? "Could not open that file." : e.getMessage();
            } catch (RuntimeException e) {
                error = "Could not open that file.";
            }
            final Document ready = result;
            final String err = error;
            main.post(() -> {
                if (ready != null) {
                    openDocument(ready);
                } else {
                    snack(err == null ? "Could not open that file." : err);
                    if (!persist) return;
                }
            });
        });
    }

    private int countPdf(Uri uri) throws IOException {
        try (android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) throw new IOException("Could not open that PDF");
            android.graphics.pdf.PdfRenderer r;
            try {
                r = new android.graphics.pdf.PdfRenderer(fd);
            } catch (SecurityException e) {
                throw new IOException("This PDF is password protected");
            }
            try {
                return r.getPageCount();
            } finally {
                r.close();
            }
        }
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open that file");
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) >= 0 && b.length() < 400_000) b.append(buf, 0, n);
            return b.toString();
        }
    }

    private String readDocx(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open that file");
            return DocxText.extract(in);
        }
    }

    private void openDocument(Document d) {
        doc = d;
        sheetIndex = 0;
        sheetTotal = 1;
        pageTotal = 0;
        editorTitle.setText(d.name);
        if (d.uris.size() == 1 && d.kind != Document.Kind.TEST) {
            recents.add(d.uris.get(0).toString(), d.name, d.kind.name());
        }
        previewMessage.setVisibility(View.GONE);
        showEditor();
        updateSummary();
        refreshPreview();
    }

    private void refreshRecents() {
        recentList.removeAllViews();
        List<RecentStore.Item> items = recents.list();
        recentEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (RecentStore.Item it : items) {
            View row = inflater.inflate(R.layout.item_recent, recentList, false);
            ((TextView) row.findViewById(R.id.recent_name)).setText(it.name);
            String kindLabel = "PDF".equals(it.kind) ? "PDF" : "IMAGES".equals(it.kind) ? "Photo" : "Text";
            ((TextView) row.findViewById(R.id.recent_meta)).setText(kindLabel + " · "
                    + DateUtils.getRelativeTimeSpanString(it.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            ImageView icon = row.findViewById(R.id.recent_icon);
            icon.setImageResource("PDF".equals(it.kind) ? R.drawable.ic_document
                    : "IMAGES".equals(it.kind) ? R.drawable.ic_photo : R.drawable.ic_text);
            row.setOnClickListener(v -> openRecent(it));
            ImageButton remove = row.findViewById(R.id.recent_remove);
            remove.setOnClickListener(v -> {
                recents.remove(it.uri);
                refreshRecents();
            });
            recentList.addView(row);
        }
    }

    private void openRecent(RecentStore.Item it) {
        Uri uri = Uri.parse(it.uri);
        // Probe the file first; if access is gone, drop the entry.
        loadExec.execute(() -> {
            boolean ok;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ok = in != null;
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
            final boolean available = ok;
            main.post(() -> {
                if (available) {
                    loadUris(Collections.singletonList(uri), false);
                } else {
                    recents.remove(it.uri);
                    refreshRecents();
                    snack("That file moved or is no longer available.");
                }
            });
        });
    }

    // ----------------------------------------------------------------- preview

    private void schedulePreview() {
        main.removeCallbacks(previewTask);
        main.postDelayed(previewTask, 150);
    }

    private void moveSheet(int delta) {
        int next = Math.max(0, Math.min(sheetTotal - 1, sheetIndex + delta));
        if (next == sheetIndex) return;
        sheetIndex = next;
        refreshPreview();
    }

    private void refreshPreview() {
        if (doc == null) return;
        final Document d = doc;
        final PrintSettings snap = s.copy();
        final int gen = ++previewGen;
        final int wanted = sheetIndex;
        previewSpinner.setVisibility(View.VISIBLE);
        previewExec.execute(() -> {
            Planner.Plan plan = null;
            Bitmap bmp = null;
            String err = null;
            int total = 1;
            int shown = 0;
            int pages = 0;
            try {
                plan = Planner.build(getApplicationContext(), d, snap);
                if (plan.error != null) {
                    err = plan.error;
                } else {
                    total = plan.sheets.size();
                    shown = Math.max(0, Math.min(wanted, total - 1));
                    pages = plan.sourcePages;
                    bmp = Rasterizer.preview(plan, plan.sheets.get(shown), d.isPhotoContent());
                }
            } catch (IOException e) {
                err = e.getMessage() == null ? "Could not preview this file." : e.getMessage();
            } catch (OutOfMemoryError e) {
                err = "Not enough memory to preview this page.";
            } catch (RuntimeException e) {
                err = "Could not preview this file.";
            } finally {
                if (plan != null) plan.close();
            }
            final Bitmap fb = bmp;
            final String fe = err;
            final int ft = total;
            final int fs = shown;
            final int fp = pages;
            main.post(() -> {
                if (gen != previewGen || isFinishing() || isDestroyed()) {
                    if (fb != null) fb.recycle();
                    return;
                }
                previewSpinner.setVisibility(View.GONE);
                if (fe != null) {
                    previewMessage.setText(fe);
                    previewMessage.setVisibility(View.VISIBLE);
                    preview.setImageDrawable(null);
                    recycleShown();
                    sheetLabel.setText("");
                    previewOk = false;
                    printButton.setEnabled(false);
                    return;
                }
                previewMessage.setVisibility(View.GONE);
                previewOk = true;
                printButton.setEnabled(!busy);
                sheetTotal = ft;
                sheetIndex = fs;
                pageTotal = fp;
                preview.setImageBitmap(fb);
                preview.setAlpha(0.25f);
                preview.animate().alpha(1f).setDuration(160).start();
                recycleShown();
                shownBitmap = fb;
                sheetLabel.setText("Sheet " + (fs + 1) + " of " + ft);
                updateSummary();
            });
        });
    }

    private void recycleShown() {
        // The ImageView has already swapped to the new bitmap by the time this runs.
        if (shownBitmap != null && !shownBitmap.isRecycled()) {
            Bitmap old = shownBitmap;
            shownBitmap = null;
            if (preview.getDrawable() == null || !isSameBitmap(old)) old.recycle();
        }
    }

    private boolean isSameBitmap(Bitmap b) {
        android.graphics.drawable.Drawable dr = preview.getDrawable();
        return dr instanceof android.graphics.drawable.BitmapDrawable
                && ((android.graphics.drawable.BitmapDrawable) dr).getBitmap() == b;
    }

    private void updateToneButton() {
        toneButton.setText(s.grayPreview ? "Grayscale" : "B&W");
    }

    private void updateSummary() {
        String[] orient = {"Auto orientation", "Portrait", "Landscape"};
        StringBuilder b = new StringBuilder();
        b.append(s.paper.label).append(" · ").append(orient[s.orientation]);
        if (s.nup() > 1) b.append(" · ").append(s.nup()).append(" per sheet");
        if (pageTotal > 0) {
            b.append(" · ").append(pageTotal).append(pageTotal == 1 ? " page" : " pages");
            if (sheetTotal != pageTotal) b.append(" on ").append(sheetTotal).append(sheetTotal == 1 ? " sheet" : " sheets");
        }
        summary.setText(b.toString());
        if (!busy) {
            String label = "Print";
            if (pageTotal > 0) {
                label = "Print " + sheetTotal + (sheetTotal == 1 ? " sheet" : " sheets");
                if (s.copies > 1) label += " × " + s.copies;
            }
            printButton.setText(label);
        }
    }

    private void setCopies(int next) {
        s.copies = Math.max(1, Math.min(50, next));
        copiesView.setText(String.valueOf(s.copies));
        s.save(this);
        updateSummary();
    }

    // ---------------------------------------------------------------- settings

    private void chips(ChipGroup g, String[] labels, int selected, IntConsumer onSelect) {
        g.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < labels.length; i++) {
            Chip c = (Chip) inflater.inflate(R.layout.chip_choice, g, false);
            c.setId(View.generateViewId());
            c.setText(labels[i]);
            g.addView(c);
            if (i == selected) c.setChecked(true);
        }
        g.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            View v = group.findViewById(ids.get(0));
            int idx = group.indexOfChild(v);
            if (idx >= 0) onSelect.accept(idx);
        });
    }

    private void changed() {
        s.save(this);
        updateSummary();
        schedulePreview();
    }

    private void showSettings() {
        int height = Math.round(getResources().getDisplayMetrics().heightPixels * 0.9f);
        if (settingsDialog == null) {
            settingsDialog = new BottomSheetDialog(this);
            settingsContent = getLayoutInflater().inflate(R.layout.sheet_settings, null);
            bindSettings(settingsContent);
            settingsDialog.setContentView(settingsContent,
                    new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            if (settingsDialog.getWindow() != null) {
                settingsDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            settingsDialog.setOnShowListener(d -> {
                BottomSheetBehavior<?> behavior = settingsDialog.getBehavior();
                // Not draggable: scrolling the settings can never close the sheet by accident.
                behavior.setDraggable(false);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                View container = settingsDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (container != null) container.setBackgroundColor(Color.TRANSPARENT);
            });
        } else {
            ViewGroup.LayoutParams lp = settingsContent.getLayoutParams();
            if (lp != null) {
                lp.height = height;
                settingsContent.setLayoutParams(lp);
            }
        }
        settingsDialog.show();
    }

    private void bindSettings(View v) {
        Paper[] papers = Paper.values();
        String[] paperNames = new String[papers.length];
        for (int i = 0; i < papers.length; i++) paperNames[i] = papers[i].label;
        chips(v.findViewById(R.id.chips_paper), paperNames, s.paper.ordinal(), i -> {
            s.paper = papers[i];
            changed();
        });
        chips(v.findViewById(R.id.chips_orientation), new String[]{"Auto", "Portrait", "Landscape"},
                s.orientation, i -> {
                    s.orientation = i;
                    changed();
                });

        View rangeLayout = v.findViewById(R.id.range_layout);
        TextInputEditText rangeEdit = v.findViewById(R.id.range_edit);
        rangeEdit.setText(s.range);
        rangeLayout.setVisibility(s.pageMode == 3 ? View.VISIBLE : View.GONE);
        chips(v.findViewById(R.id.chips_pagemode), new String[]{"All", "Odd", "Even", "Custom"},
                s.pageMode, i -> {
                    s.pageMode = i;
                    rangeLayout.setVisibility(i == 3 ? View.VISIBLE : View.GONE);
                    changed();
                });
        rangeEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(Editable e) {
                s.range = e.toString();
                if (s.pageMode == 3) changed();
            }
        });

        MaterialSwitch reverse = v.findViewById(R.id.sw_reverse);
        reverse.setChecked(s.reverse);
        reverse.setOnCheckedChangeListener((b, on) -> {
            s.reverse = on;
            changed();
        });
        MaterialSwitch reverseEven = v.findViewById(R.id.sw_reverse_even);
        reverseEven.setChecked(s.reverseEven);
        reverseEven.setVisibility(s.manualDuplex ? View.VISIBLE : View.GONE);
        reverseEven.setOnCheckedChangeListener((b, on) -> {
            s.reverseEven = on;
            s.save(this);
        });
        MaterialSwitch duplex = v.findViewById(R.id.sw_duplex);
        duplex.setChecked(s.manualDuplex);
        duplex.setOnCheckedChangeListener((b, on) -> {
            s.manualDuplex = on;
            reverseEven.setVisibility(on ? View.VISIBLE : View.GONE);
            s.save(this);
        });

        chips(v.findViewById(R.id.chips_nup), new String[]{"1", "2", "4", "6", "9"}, s.nupIndex, i -> {
            s.nupIndex = i;
            changed();
        });
        chips(v.findViewById(R.id.chips_scale), new String[]{"Fit", "Fill (crop)", "Actual size"}, s.scaling, i -> {
            s.scaling = i;
            changed();
        });
        chips(v.findViewById(R.id.chips_margin), new String[]{"None", "Narrow", "Normal", "Wide"}, s.margin, i -> {
            s.margin = i;
            changed();
        });
        chips(v.findViewById(R.id.chips_position), new String[]{"Center", "Top"}, s.position, i -> {
            s.position = i;
            changed();
        });
        MaterialSwitch pageNum = v.findViewById(R.id.sw_pagenum);
        pageNum.setChecked(s.pageNumbers);
        pageNum.setOnCheckedChangeListener((b, on) -> {
            s.pageNumbers = on;
            changed();
        });
        MaterialSwitch border = v.findViewById(R.id.sw_border);
        border.setChecked(s.border);
        border.setOnCheckedChangeListener((b, on) -> {
            s.border = on;
            changed();
        });

        chips(v.findViewById(R.id.chips_tone), new String[]{"Auto", "Text (sharp)", "Photo (halftone)"}, s.tone, i -> {
            s.tone = i;
            changed();
        });
        TextView densityValue = v.findViewById(R.id.slider_density_value);
        Slider density = v.findViewById(R.id.slider_density);
        density.setValue(s.density);
        densityValue.setText(s.density + " of 5");
        density.addOnChangeListener((sl, value, fromUser) -> {
            s.density = Math.round(value);
            densityValue.setText(s.density + " of 5");
            s.save(this);
        });
        TextView brightValue = v.findViewById(R.id.slider_brightness_value);
        Slider bright = v.findViewById(R.id.slider_brightness);
        bright.setValue(s.brightness);
        brightValue.setText(String.valueOf(s.brightness));
        bright.addOnChangeListener((sl, value, fromUser) -> {
            s.brightness = Math.round(value);
            brightValue.setText(String.valueOf(s.brightness));
            if (fromUser) changed();
        });
        TextView contrastValue = v.findViewById(R.id.slider_contrast_value);
        Slider contrast = v.findViewById(R.id.slider_contrast);
        contrast.setValue(s.contrast);
        contrastValue.setText(s.contrast + "%");
        contrast.addOnChangeListener((sl, value, fromUser) -> {
            s.contrast = Math.round(value);
            contrastValue.setText(s.contrast + "%");
            if (fromUser) changed();
        });
        MaterialSwitch toner = v.findViewById(R.id.sw_toner);
        toner.setChecked(s.toner);
        toner.setOnCheckedChangeListener((b, on) -> {
            s.toner = on;
            s.save(this);
        });
        MaterialSwitch collate = v.findViewById(R.id.sw_collate);
        collate.setChecked(s.collate);
        collate.setOnCheckedChangeListener((b, on) -> {
            s.collate = on;
            s.save(this);
        });

        chips(v.findViewById(R.id.chips_textsize), new String[]{"Small", "Medium", "Large"}, s.textSizeIndex, i -> {
            s.textSizeIndex = i;
            changed();
        });
        chips(v.findViewById(R.id.chips_rotation), new String[]{"0°", "90°", "180°", "270°"}, s.rotation, i -> {
            s.rotation = i;
            changed();
        });
        v.findViewById(R.id.btn_done).setOnClickListener(x -> settingsDialog.dismiss());
        v.findViewById(R.id.btn_close).setOnClickListener(x -> settingsDialog.dismiss());
        v.findViewById(R.id.btn_reset).setOnClickListener(x -> {
            int keepCopies = s.copies;
            s.assign(new PrintSettings());
            s.copies = keepCopies;
            s.save(this);
            settingsDialog.dismiss();
            settingsDialog = null;
            settingsContent = null;
            updateToneButton();
            updateSummary();
            schedulePreview();
            snack("Settings reset to defaults");
        });
        settingsBuilt = true;
    }

    // ------------------------------------------------------------------- print

    private void onPrint() {
        if (doc == null || busy) return;
        printButton.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (conn.state() != PrinterConnection.State.CONNECTED) {
            snack("The printer isn't connected yet. Trying now…");
            conn.connectNow();
            return;
        }
        final PrintSettings snap = s.copy();
        if (snap.manualDuplex) {
            snap.pageMode = 1;
            startJob(snap, () -> askFlip(snap));
        } else {
            startJob(snap, null);
        }
    }

    private void askFlip(PrintSettings front) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Flip the pages")
                .setMessage("The odd pages are printed. Put the printed stack back in the input tray so the "
                        + "blank side is printed next, then tap Print back sides.\n\n"
                        + "If the back sides come out upside down or in the wrong order, change the "
                        + "\"Reverse back sides\" option in Settings.")
                .setPositiveButton("Print back sides", (d, w) -> {
                    PrintSettings back = front.copy();
                    back.pageMode = 2;
                    back.reverse = front.reverseEven;
                    if (conn.state() != PrinterConnection.State.CONNECTED) {
                        snack("The printer isn't connected.");
                        conn.connectNow();
                    } else {
                        startJob(back, null);
                    }
                })
                .setNegativeButton("Done", null)
                .show();
    }

    private void setJob(boolean running, String text, int percent) {
        jobPanel.setVisibility(running ? View.VISIBLE : View.GONE);
        printButton.setEnabled(!running && previewOk);
        if (running) {
            jobText.setText(text);
            jobProgress.setProgress(percent);
        }
    }

    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            if (wakeLock == null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noko:print");
            if (!wakeLock.isHeld()) wakeLock.acquire(15 * 60 * 1000L);
        } catch (RuntimeException ignored) {
            // printing still works without it
        }
    }

    private void releaseWake() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (RuntimeException ignored) {
            // already released
        }
    }

    private void startJob(PrintSettings snap, Runnable after) {
        final Document d = doc;
        busy = true;
        cancelled = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        acquireWake();
        setJob(true, "Preparing…", 0);
        try {
            printExec.execute(() -> runJob(d, snap, after));
        } catch (RuntimeException e) {
            finishJob("Could not start printing. Please try again.", false, snap, after);
        }
    }

    private void runJob(Document d, PrintSettings snap, Runnable after) {
        Planner.Plan plan = null;
        UsbLink[] link = new UsbLink[1];
        String message;
        boolean ok = false;
        try {
            plan = Planner.build(getApplicationContext(), d, snap);
            if (plan.error != null) throw new IOException(plan.error);
            link[0] = conn.requireLink();
            int sheets = plan.sheets.size();
            int copies = Math.max(1, snap.copies);
            boolean collate = snap.collate && copies > 1 && sheets > 1;
            int total = collate ? sheets * copies : sheets;
            int done = 0;
            byte[][] cache = new byte[sheets][];
            long used = 0;
            int rounds = collate ? copies : 1;
            for (int c = 0; c < rounds; c++) {
                for (int i = 0; i < sheets; i++) {
                    if (cancelled) throw new IOException("Cancelled");
                    postJob("Printing sheet " + (done + 1) + " of " + total, done * 100 / total);
                    byte[] job = cache[i];
                    if (job == null) {
                        job = encode(plan, plan.sheets.get(i), d.isPhotoContent(), collate ? 1 : copies);
                        if (collate && used + job.length < 48L * 1024 * 1024) {
                            cache[i] = job;
                            used += job.length;
                        }
                    }
                    send(link, job);
                    done++;
                }
            }
            ok = true;
            message = "Sent to the printer: " + sheets + (sheets == 1 ? " sheet" : " sheets")
                    + (copies > 1 ? " × " + copies : "") + ".";
        } catch (IOException e) {
            String m = e.getMessage() == null ? "Printing failed." : e.getMessage();
            if ("Cancelled".equals(m)) {
                message = "Print cancelled.";
            } else {
                message = m;
                if (link[0] != null && !link[0].isOpen()) conn.reportFailure(link[0], m);
            }
        } catch (OutOfMemoryError e) {
            message = "Not enough memory for this page. Try fewer pages per sheet or a smaller photo.";
        } catch (Throwable e) {
            message = "Printing stopped unexpectedly. Please try again.";
        } finally {
            if (plan != null) plan.close();
        }
        finishJob(message, ok, snap, after);
    }

    /** Writes one job; if the USB handle went stale, reconnects once and sends it again. */
    private void send(UsbLink[] holder, byte[] job) throws IOException {
        try {
            holder[0].write(job);
        } catch (IOException first) {
            if (cancelled) throw first;
            holder[0] = conn.reopenBlocking();
            holder[0].write(job);
        }
    }

    private void finishJob(String message, boolean ok, PrintSettings snap, Runnable after) {
        main.post(() -> {
            busy = false;
            releaseWake();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            setJob(false, "", 0);
            updateSummary();
            if (isFinishing() || isDestroyed()) return;
            if (ok) {
                snack(message);
                if (after != null) after.run();
            } else if ("Print cancelled.".equals(message)) {
                snack(message);
            } else {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Couldn't print")
                        .setMessage(message + "\n\nCheck that the printer is on, has paper, and the USB cable is firmly connected.")
                        .setPositiveButton("Try again", (dd, w) -> {
                            if (conn.state() == PrinterConnection.State.CONNECTED) {
                                startJob(snap, after);
                            } else {
                                snack("Reconnecting to the printer…");
                                conn.connectNow();
                            }
                        })
                        .setNegativeButton("Close", null)
                        .show();
            }
        });
    }

    private void postJob(String text, int percent) {
        main.post(() -> {
            if (busy) setJob(true, text, percent);
        });
    }

    private byte[] encode(Planner.Plan plan, Planner.Sheet sheet, boolean photo, int copies) throws IOException {
        PrintSettings st = plan.settings;
        Paper p = st.paper;
        byte[] bits = Rasterizer.raster(plan, sheet, photo, () -> cancelled);
        int w = p.rasterWidthPx();
        int h = p.rasterHeightPx();
        return XqxEncoder.encode(bits, w, h, Paper.stride(w), Paper.DPI, Paper.DPI,
                p.code, st.density, st.toner ? 1 : 0, copies);
    }
}
