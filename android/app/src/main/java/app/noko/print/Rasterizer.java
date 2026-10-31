package app.noko.print;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.IOException;

public final class Rasterizer {

    public interface Progress {
        void onPage(int index, int count);
    }

    private Rasterizer() {}

    public static int pdfPageCount(ParcelFileDescriptor fd) throws IOException {
        PdfRenderer renderer = new PdfRenderer(fd);
        try {
            return renderer.getPageCount();
        } finally {
            renderer.close();
        }
    }

    public static byte[] testPage(Paper paper) { return testPage(paper, false); }

    public static byte[] testPage(Paper paper, boolean landscape) {
        int width = paper.widthPx(landscape);
        int height = paper.heightPx(landscape);
        int stride = Paper.stride(width);
        byte[] bits = new byte[stride * height];
        drawStrips(width, height, bits, stride, (canvas, w, h) -> drawTest(canvas, w, h));
        return bits;
    }

    public static byte[] textPage(String text, Paper paper) { return textPage(text, paper, false); }

    public static byte[] textPage(String text, Paper paper, boolean landscape) {
        int width = paper.widthPx(landscape);
        int height = paper.heightPx(landscape);
        int stride = Paper.stride(width);
        byte[] bits = new byte[stride * height];
        int margin = Math.round(width * 0.07f);
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(width / 42f);
        StaticLayout layout = StaticLayout.Builder
                .obtain(text == null ? "" : text, 0, text == null ? 0 : text.length(), paint, width - margin * 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .build();
        drawStrips(width, height, bits, stride, (canvas, w, h) -> {
            canvas.translate(margin, margin);
            layout.draw(canvas);
        });
        return bits;
    }

    public static java.util.List<byte[]> textPages(String text, Paper paper, boolean landscape) {
        int width = paper.widthPx(landscape);
        int height = paper.heightPx(landscape);
        int stride = Paper.stride(width);
        int margin = Math.round(width * 0.07f);
        int contentHeight = height - margin * 2;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(width / 42f);
        String value = text == null ? "" : text;
        StaticLayout layout = StaticLayout.Builder
                .obtain(value, 0, value.length(), paint, width - margin * 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.25f)
                .setIncludePad(true)
                .build();
        java.util.List<byte[]> pages = new java.util.ArrayList<>();
        int line = 0;
        while (line < layout.getLineCount() || pages.isEmpty()) {
            int startLine = line;
            int startTop = layout.getLineTop(startLine);
            int endLine = startLine;
            while (endLine < layout.getLineCount()
                    && layout.getLineBottom(endLine) - startTop <= contentHeight) endLine++;
            if (endLine == startLine && endLine < layout.getLineCount()) endLine++;
            final int offsetY = startTop;
            byte[] bits = new byte[stride * height];
            drawStrips(width, height, bits, stride, (canvas, w, h) -> {
                canvas.save();
                canvas.clipRect(margin, margin, w - margin, h - margin);
                canvas.translate(margin, margin - offsetY);
                layout.draw(canvas);
                canvas.restore();
            });
            pages.add(bits);
            line = endLine;
            if (line >= layout.getLineCount()) break;
        }
        return pages;
    }

    public static byte[] imagePage(Bitmap source, Paper paper) { return imagePage(source, paper, false); }

    public static byte[] imagePage(Bitmap source, Paper paper, boolean landscape) {
        return imagePage(source, paper, landscape, 1, 0, 0);
    }

    public static byte[] imagePage(Bitmap source, Paper paper, boolean landscape,
                                   int marginMode, int scaleMode, int positionMode) {
        int width = paper.widthPx(landscape);
        int height = paper.heightPx(landscape);
        int stride = Paper.stride(width);
        byte[] bits = new byte[stride * height];
        drawStrips(width, height, bits, stride, (canvas, w, h) -> {
            float margin = (marginMode == 0 ? 0f : marginMode == 1 ? 0.025f : marginMode == 2 ? 0.05f : 0.08f) * w;
            float maxW = w - margin * 2f;
            float maxH = h - margin * 2f;
            float fit = Math.min(maxW / source.getWidth(), maxH / source.getHeight());
            float fill = Math.max(maxW / source.getWidth(), maxH / source.getHeight());
            float scale = scaleMode == 1 ? fill : scaleMode == 2 ? maxW / source.getWidth() : fit;
            float dw = source.getWidth() * scale;
            float dh = source.getHeight() * scale;
            float left = (w - dw) / 2f;
            float top = positionMode == 1 ? margin : positionMode == 2 ? h - dh - margin : (h - dh) / 2f;
            RectF dest = new RectF(left, top, left + dw, top + dh);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(source, null, dest, paint);
        });
        return bits;
    }

    public static byte[] pdfPage(PdfRenderer renderer, int index, Paper paper) { return pdfPage(renderer, index, paper, false); }

    public static byte[] pdfPage(PdfRenderer renderer, int index, Paper paper, boolean landscape) {
        return pdfPage(renderer, index, paper, landscape, 0, 0, 0);
    }

    public static byte[] pdfPage(PdfRenderer renderer, int index, Paper paper, boolean landscape,
                                 int marginMode, int scaleMode, int positionMode) {
        int width = paper.widthPx(landscape);
        int height = paper.heightPx(landscape);
        int stride = Paper.stride(width);
        byte[] bits = new byte[stride * height];
        PdfRenderer.Page page = renderer.openPage(index);
        try {
            int stripH = 64;
            Bitmap strip = Bitmap.createBitmap(width, stripH, Bitmap.Config.ARGB_8888);
            try {
                int[] row = new int[width];
                for (int y = 0; y < height; y += stripH) {
                    int rows = Math.min(stripH, height - y);
                    strip.eraseColor(Color.WHITE);
                    Matrix matrix = new Matrix();
                    float margin = marginMode == 0 ? 0.0f : marginMode == 1 ? 0.025f : marginMode == 2 ? 0.05f : 0.08f;
                    float availableW = width * (1f - 2f * margin);
                    float availableH = height * (1f - 2f * margin);
                    float sx = availableW / (float) page.getWidth();
                    float sy = availableH / (float) page.getHeight();
                    float scale = scaleMode == 1 ? Math.max(sx, sy) : scaleMode == 2 ? sx : Math.min(sx, sy);
                    float drawnW = page.getWidth() * scale;
                    float drawnH = page.getHeight() * scale;
                    float offsetX = (width - drawnW) / 2f;
                    float offsetY = positionMode == 1 ? height * margin : positionMode == 2 ? height - drawnH - height * margin : (height - drawnH) / 2f;
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(offsetX, offsetY - y);
                    page.render(strip, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    pack(strip, rows, y, width, height, stride, bits, row);
                }
            } finally {
                strip.recycle();
            }
        } finally {
            page.close();
        }
        return bits;
    }

    private interface Drawer {
        void draw(Canvas canvas, int width, int height);
    }

    private static void drawStrips(int width, int height, byte[] bits, int stride, Drawer drawer) {
        int stripH = 64;
        Bitmap strip = Bitmap.createBitmap(width, stripH, Bitmap.Config.ARGB_8888);
        try {
            int[] row = new int[width];
            for (int y = 0; y < height; y += stripH) {
                int rows = Math.min(stripH, height - y);
                strip.eraseColor(Color.WHITE);
                Canvas canvas = new Canvas(strip);
                canvas.save();
                canvas.translate(0f, -y);
                drawer.draw(canvas, width, height);
                canvas.restore();
                pack(strip, rows, y, width, height, stride, bits, row);
            }
        } finally {
            strip.recycle();
        }
    }

    private static void pack(Bitmap strip, int rows, int y, int width, int height, int stride, byte[] bits, int[] row) {
        for (int r = 0; r < rows && y + r < height; r++) {
            strip.getPixels(row, 0, width, 0, r, width, 1);
            int base = (y + r) * stride;
            for (int x = 0; x < width; x++) {
                int color = row[x];
                int red = (color >> 16) & 255;
                int green = (color >> 8) & 255;
                int blue = color & 255;
                int luma = (red * 54 + green * 183 + blue * 19) >> 8;
                // Clean monochrome thresholding avoids the Bayer-screen white pinholes
                // and dotted texture produced by ordered dithering on this 1-bit engine.
                // Anti-aliased edge pixels are retained while paper/background stays white.
                if (luma < 176) bits[base + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
        }
    }

    private static void drawTest(Canvas canvas, int width, int height) {
        float margin = width * 0.08f;
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(Color.BLACK);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(Math.max(2f, width * 0.0035f));
        canvas.drawRect(margin, margin, width - margin, height - margin, ink);

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.BLACK);
        fill.setStyle(Paint.Style.FILL);

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.BLACK);
        title.setFakeBoldText(true);
        title.setTextSize(width * 0.075f);
        canvas.drawText("Noko Print", margin * 1.5f, height * 0.22f, title);

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.BLACK);
        body.setTextSize(width * 0.034f);
        float x = margin * 1.5f;
        canvas.drawText("HP LaserJet M1005 MFP", x, height * 0.30f, body);
        canvas.drawText("Machine KJ73EDG", x, height * 0.35f, body);
        canvas.drawText("600 dpi  ·  plain paper", x, height * 0.40f, body);

        Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
        rule.setColor(Color.BLACK);
        rule.setStrokeWidth(Math.max(2f, width * 0.0025f));
        canvas.drawLine(x, height * 0.45f, width - margin * 1.5f, height * 0.45f, rule);

        body.setTextSize(width * 0.032f);
        canvas.drawText("If you can read this, the phone", x, height * 0.52f, body);
        canvas.drawText("is printing through the USB cable.", x, height * 0.57f, body);

        float box = width * 0.08f;
        float y = height * 0.68f;
        for (int i = 0; i < 8; i++) {
            if ((i & 1) == 0) {
                canvas.drawRect(x + i * box, y, x + (i + 1) * box, y + box, fill);
            } else {
                canvas.drawRect(x + i * box, y, x + (i + 1) * box, y + box, ink);
            }
        }
    }
}
