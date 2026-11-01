package app.noko.print;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Turns a document + settings into a list of sheets, each holding positioned tiles. */
public final class Planner {
    private Planner() {}

    public static final class Tile {
        public final Source src;
        public RectF boxMm;
        public RectF destMm;
        public final int number;

        Tile(Source src, int number) {
            this.src = src;
            this.number = number;
        }
    }

    public static final class Sheet {
        public final List<Tile> tiles = new ArrayList<>();
        public final int index;
        public boolean landscape;
        public boolean laidOut;

        Sheet(int index) { this.index = index; }

        public float widthMm(Paper p) { return p.sheetWidthMm(landscape); }

        public float heightMm(Paper p) { return p.sheetHeightMm(landscape); }
    }

    public static final class Plan implements Closeable {
        public final List<Sheet> sheets = new ArrayList<>();
        public final PrintSettings settings;
        public final Document doc;
        public int sourcePages;
        public String error;
        private ParcelFileDescriptor fd;
        private PdfRenderer renderer;

        Plan(PrintSettings settings, Document doc) {
            this.settings = settings;
            this.doc = doc;
        }

        public Paper paper() { return settings.paper; }

        @Override
        public void close() {
            for (Sheet s : sheets) {
                for (Tile t : s.tiles) t.src.release();
            }
            if (renderer != null) {
                try { renderer.close(); } catch (RuntimeException ignored) { /* closed */ }
                renderer = null;
            }
            if (fd != null) {
                try { fd.close(); } catch (IOException ignored) { /* closed */ }
                fd = null;
            }
        }
    }

    public static Plan build(Context ctx, Document doc, PrintSettings s) throws IOException {
        Plan plan = new Plan(s, doc);
        try {
            int nup = s.nup();
            List<Source> all = new ArrayList<>();
            switch (doc.kind) {
                case PDF: {
                    plan.fd = ctx.getContentResolver().openFileDescriptor(doc.uris.get(0), "r");
                    if (plan.fd == null) throw new IOException("Could not open that PDF");
                    try {
                        plan.renderer = new PdfRenderer(plan.fd);
                    } catch (SecurityException e) {
                        throw new IOException("This PDF is password protected");
                    }
                    int n = plan.renderer.getPageCount();
                    for (int i = 0; i < n; i++) all.add(new Sources.Pdf(plan.renderer, i, nup == 1));
                    break;
                }
                case IMAGES:
                    for (android.net.Uri u : doc.uris) all.add(new Sources.Img(ctx, u, s.rotation * 90));
                    break;
                case TEXT:
                    all = textPages(doc.text == null ? "" : doc.text, s, nup);
                    break;
                default: {
                    boolean land = nup == 1 && s.orientation == 2;
                    float pw = s.paper.sheetWidthMm(land) / 25.4f * 72f;
                    float ph = s.paper.sheetHeightMm(land) / 25.4f * 72f;
                    all.add(new Sources.Test(pw, ph, s.paper.label));
                    break;
                }
            }
            plan.sourcePages = all.size();
            int[] picked;
            try {
                picked = pick(s, all.size());
            } catch (IllegalArgumentException e) {
                plan.error = e.getMessage();
                return plan;
            }
            Sheet current = null;
            for (int k = 0; k < picked.length; k++) {
                if (k % nup == 0) {
                    current = new Sheet(plan.sheets.size());
                    plan.sheets.add(current);
                }
                current.tiles.add(new Tile(all.get(picked[k]), picked[k] + 1));
            }
            return plan;
        } catch (IOException | RuntimeException e) {
            plan.close();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage() == null ? "Could not read that file" : e.getMessage());
        }
    }

    private static int[] pick(PrintSettings s, int total) {
        if (total == 0) throw new IllegalArgumentException("This document has no pages");
        int[] idx;
        switch (s.pageMode) {
            case 1:
            case 2: {
                int start = s.pageMode == 1 ? 0 : 1;
                int count = 0;
                for (int i = start; i < total; i += 2) count++;
                if (count == 0) throw new IllegalArgumentException(
                        s.pageMode == 1 ? "No odd pages" : "No even pages in a one-page document");
                idx = new int[count];
                int j = 0;
                for (int i = start; i < total; i += 2) idx[j++] = i;
                break;
            }
            case 3:
                idx = PageSpec.parse(s.range, total);
                break;
            default:
                idx = new int[total];
                for (int i = 0; i < total; i++) idx[i] = i;
        }
        if (s.reverse) {
            for (int a = 0, b = idx.length - 1; a < b; a++, b--) {
                int t = idx[a];
                idx[a] = idx[b];
                idx[b] = t;
            }
        }
        return idx;
    }

    private static List<Source> textPages(String text, PrintSettings s, int nup) {
        boolean land = nup == 1 && s.orientation == 2;
        float pageW = s.paper.sheetWidthMm(land) / 25.4f * 72f;
        float pageH = s.paper.sheetHeightMm(land) / 25.4f * 72f;
        float margin = 48f;
        String clean = text.replace("\r\n", "\n").replace('\r', '\n');
        if (clean.length() > 400_000) clean = clean.substring(0, 400_000);
        TextPaint tp = Sources.textPaint(s.textSize());
        int width = Math.max(50, (int) (pageW - 2 * margin));
        StaticLayout layout = StaticLayout.Builder
                .obtain(clean, 0, clean.length(), tp, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.2f)
                .build();
        List<Source> pages = new ArrayList<>();
        int lines = layout.getLineCount();
        float avail = pageH - 2 * margin;
        int start = 0;
        while (start < lines) {
            float top = layout.getLineTop(start);
            int end = start;
            while (end < lines && layout.getLineBottom(end) - top <= avail) end++;
            if (end == start) end = start + 1;
            pages.add(new Sources.Text(layout, start, end, pageW, pageH, margin));
            start = end;
        }
        if (pages.isEmpty()) pages.add(new Sources.Text(layout, 0, 0, pageW, pageH, margin));
        return pages;
    }

    /** Decide orientation and position every tile of a sheet. Safe to call twice. */
    public static void layout(Plan plan, Sheet sheet) {
        if (sheet.laidOut) return;
        PrintSettings s = plan.settings;
        int nup = s.nup();
        Tile first = sheet.tiles.get(0);
        if (s.orientation == 1) {
            sheet.landscape = false;
        } else if (s.orientation == 2) {
            sheet.landscape = true;
        } else {
            boolean srcLandscape = first.src.widthMm() > first.src.heightMm();
            sheet.landscape = nup == 2 ? !srcLandscape : srcLandscape;
        }
        float w = sheet.widthMm(s.paper);
        float h = sheet.heightMm(s.paper);
        float m = Math.min(s.marginMm(), Math.min(w, h) / 4f);
        float aw = w - 2 * m;
        float ah = h - 2 * m;
        int cols;
        int rows;
        switch (nup) {
            case 2: cols = sheet.landscape ? 2 : 1; rows = sheet.landscape ? 1 : 2; break;
            case 4: cols = 2; rows = 2; break;
            case 6: cols = sheet.landscape ? 3 : 2; rows = sheet.landscape ? 2 : 3; break;
            case 9: cols = 3; rows = 3; break;
            default: cols = 1; rows = 1;
        }
        float gutter = nup > 1 ? 4f : 0f;
        float cw = (aw - gutter * (cols - 1)) / cols;
        float ch = (ah - gutter * (rows - 1)) / rows;
        for (int k = 0; k < sheet.tiles.size(); k++) {
            Tile t = sheet.tiles.get(k);
            int r = k / cols;
            int c = k % cols;
            float left = m + c * (cw + gutter);
            float top = m + r * (ch + gutter);
            t.boxMm = new RectF(left, top, left + cw, top + ch);
            float sw = Math.max(0.1f, t.src.widthMm());
            float sh = Math.max(0.1f, t.src.heightMm());
            float scale;
            if (s.scaling == 2) scale = 1f;
            else if (s.scaling == 1) scale = Math.max(cw / sw, ch / sh);
            else scale = Math.min(cw / sw, ch / sh);
            float dw = sw * scale;
            float dh = sh * scale;
            float dx = left + (cw - dw) / 2f;
            float dy = s.position == 1 ? top : top + (ch - dh) / 2f;
            t.destMm = new RectF(dx, dy, dx + dw, dy + dh);
        }
        sheet.laidOut = true;
    }
}
