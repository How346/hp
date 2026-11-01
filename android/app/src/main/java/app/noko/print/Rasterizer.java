package app.noko.print;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import java.io.IOException;

/** Renders sheets: preview bitmaps for the screen, 1-bit rasters for the printer. */
public final class Rasterizer {
    private Rasterizer() {}

    public interface Cancel {
        boolean isCancelled();
    }

    private static final int[] BAYER4 = {
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5};

    /** Tone curve and black/white decision shared by preview and print. */
    public static final class Tone {
        final boolean halftone;
        final int cutoff = 176;
        final int[] lut = new int[256];

        Tone(PrintSettings s, boolean photoContent) {
            if (s.tone == 1) halftone = false;
            else if (s.tone == 2) halftone = true;
            else halftone = photoContent;
            float contrast = s.contrast / 100f;
            for (int i = 0; i < 256; i++) {
                int v = Math.round((i - 128) * contrast + 128 + s.brightness * 2f);
                lut[i] = Math.max(0, Math.min(255, v));
            }
        }

        boolean black(int v, int x, int y) {
            if (!halftone) return v < cutoff;
            int t = BAYER4[((y >> 1) & 3) * 4 + ((x >> 1) & 3)] * 16 + 8;
            return v < t;
        }
    }

    public static Tone tone(PrintSettings s, boolean photoContent) {
        return new Tone(s, photoContent);
    }

    private static int luma(int c) {
        return (((c >> 16) & 255) * 54 + ((c >> 8) & 255) * 183 + (c & 255) * 19) >> 8;
    }

    /** Draw one region of a sheet onto target. y0 is the sheet row at target's top. */
    public static void renderRegion(Planner.Plan plan, Planner.Sheet sheet, Bitmap target, int y0,
                                    float dpi, boolean preview) {
        PrintSettings s = plan.settings;
        Canvas canvas = new Canvas(target);
        canvas.drawColor(Color.WHITE);
        float px = dpi / 25.4f;
        for (Planner.Tile t : sheet.tiles) {
            RectF dest = scale(t.destMm, px);
            RectF box = scale(t.boxMm, px);
            t.src.draw(target, canvas, y0, dest, box, dpi, preview);
        }
        canvas.save();
        canvas.translate(0, -y0);
        if (s.border) {
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(Color.BLACK);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1f, dpi / 200f));
            for (Planner.Tile t : sheet.tiles) canvas.drawRect(scale(t.boxMm, px), line);
        }
        if (s.pageNumbers) {
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.BLACK);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(9f / 72f * dpi);
            float cx = sheet.widthMm(s.paper) * px / 2f;
            float cy = (sheet.heightMm(s.paper) - 2.5f) * px;
            canvas.drawText((sheet.index + 1) + " / " + plan.sheets.size(), cx, cy, text);
        }
        canvas.restore();
    }

    private static RectF scale(RectF mm, float px) {
        return new RectF(mm.left * px, mm.top * px, mm.right * px, mm.bottom * px);
    }

    private static void prepareSheet(Planner.Sheet sheet, Planner.Plan plan, float dpi, boolean preview)
            throws IOException {
        float px = dpi / 25.4f;
        for (Planner.Tile t : sheet.tiles) t.src.prepare(scale(t.destMm, px), dpi, preview);
    }

    private static void releaseSheet(Planner.Sheet sheet) {
        for (Planner.Tile t : sheet.tiles) t.src.release();
    }

    /** Screen preview, drawn the way the printer will print it. */
    public static Bitmap preview(Planner.Plan plan, Planner.Sheet sheet, boolean photoContent) throws IOException {
        PrintSettings s = plan.settings;
        Planner.layout(plan, sheet);
        final float dpi = 150f;
        int w = Math.max(1, Math.round(sheet.widthMm(s.paper) / 25.4f * dpi));
        int h = Math.max(1, Math.round(sheet.heightMm(s.paper) / 25.4f * dpi));
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            prepareSheet(sheet, plan, dpi, true);
            renderRegion(plan, sheet, bmp, 0, dpi, true);
        } finally {
            releaseSheet(sheet);
        }
        Tone tone = new Tone(s, photoContent);
        int[] px = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(px, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int v = tone.lut[luma(px[x])];
                if (s.grayPreview) px[x] = Color.rgb(v, v, v);
                else px[x] = tone.black(v, x, y) ? Color.BLACK : Color.WHITE;
            }
            bmp.setPixels(px, 0, w, 0, y, w, 1);
        }
        return bmp;
    }

    /** Full-page 1-bit raster (always portrait, as the printer expects). */
    public static byte[] raster(Planner.Plan plan, Planner.Sheet sheet, boolean photoContent, Cancel cancel)
            throws IOException {
        PrintSettings s = plan.settings;
        Paper paper = s.paper;
        Planner.layout(plan, sheet);
        boolean rotate = sheet.landscape;
        int wl = paper.sheetWidthPx(rotate);
        int hl = paper.sheetHeightPx(rotate);
        int rasterW = rotate ? hl : wl;
        int rasterH = rotate ? wl : hl;
        int stride = Paper.stride(rasterW);
        byte[] bits = new byte[stride * rasterH];
        Tone tone = new Tone(s, photoContent);
        int stripH = Math.max(64, Math.min(512, 2_000_000 / Math.max(1, wl)));
        Bitmap strip = Bitmap.createBitmap(wl, stripH, Bitmap.Config.ARGB_8888);
        int[] px = new int[wl * stripH];
        try {
            prepareSheet(sheet, plan, Paper.DPI, false);
            for (int y = 0; y < hl; y += stripH) {
                if (cancel != null && cancel.isCancelled()) throw new IOException("Cancelled");
                int rows = Math.min(stripH, hl - y);
                renderRegion(plan, sheet, strip, y, Paper.DPI, false);
                strip.getPixels(px, 0, wl, 0, 0, wl, rows);
                for (int r = 0; r < rows; r++) {
                    int sy = y + r;
                    int rowBase = r * wl;
                    for (int x = 0; x < wl; x++) {
                        int v = tone.lut[luma(px[rowBase + x])];
                        if (!tone.black(v, x, sy)) continue;
                        if (rotate) {
                            int xr = hl - 1 - sy;
                            bits[x * stride + (xr >> 3)] |= (byte) (0x80 >> (xr & 7));
                        } else {
                            bits[sy * stride + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                        }
                    }
                }
            }
        } finally {
            strip.recycle();
            releaseSheet(sheet);
        }
        return bits;
    }
}
