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

    public static byte[] testPage(Paper paper) {
        int width = paper.widthPx();
        int height = paper.heightPx();
        int stride = Paper.stride(width);
        byte[] bits = new byte[stride * height];
        drawStrips(width, height, bits, stride, (canvas, w, h) -> drawTest(canvas, w, h));
        return bits;
    }

    public static byte[] textPage(String text, Paper paper) {
        int width = paper.widthPx();
        int height = paper.heightPx();
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

    public static byte[] imagePage(Bitmap source, Paper paper) {
        int width = paper.widthPx();
        int height = paper.heightPx();
        int stride = Paper.stride(width);
        byte[] bits = new byte[stride * height];
        drawStrips(width, height, bits, stride, (canvas, w, h) -> {
            float margin = w * 0.05f;
            float maxW = w - margin * 2f;
            float maxH = h - margin * 2f;
            float scale = Math.min(maxW / source.getWidth(), maxH / source.getHeight());
            float dw = source.getWidth() * scale;
            float dh = source.getHeight() * scale;
            RectF dest = new RectF(margin + (maxW - dw) / 2f, margin + (maxH - dh) / 2f, margin + (maxW - dw) / 2f + dw, margin + (maxH - dh) / 2f + dh);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(source, null, dest, paint);
        });
        return bits;
    }

    public static byte[] pdfPage(PdfRenderer renderer, int index, Paper paper) {
        int width = paper.widthPx();
        int height = paper.heightPx();
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
                    float sx = width / (float) page.getWidth();
                    float sy = height / (float) page.getHeight();
                    matrix.setScale(sx, sy);
                    matrix.postTranslate(0f, -y);
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
                if (luma < 176) {
                    bits[base + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
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
