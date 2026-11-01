package app.noko.print;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.IOException;
import java.io.InputStream;

public final class Sources {
    private Sources() {}

    private static RectF shift(RectF r, int y0) {
        return new RectF(r.left, r.top - y0, r.right, r.bottom - y0);
    }

    /** A page of a PDF. */
    public static final class Pdf extends Source {
        private final PdfRenderer renderer;
        private final int index;
        private final boolean direct;
        private float w = -1f;
        private float h;
        private Bitmap cache;

        public Pdf(PdfRenderer renderer, int index, boolean direct) {
            this.renderer = renderer;
            this.index = index;
            this.direct = direct;
        }

        private void ensureSize() {
            if (w > 0) return;
            PdfRenderer.Page page = renderer.openPage(index);
            try {
                w = Math.max(1, page.getWidth());
                h = Math.max(1, page.getHeight());
            } finally {
                page.close();
            }
        }

        @Override public float widthMm() { ensureSize(); return w / 72f * 25.4f; }

        @Override public float heightMm() { ensureSize(); return h / 72f * 25.4f; }

        @Override
        public void prepare(RectF dest, float dpi, boolean preview) {
            if (direct || cache != null) return;
            ensureSize();
            float k = Math.min(1f, 300f / dpi);
            int pw = Math.max(1, Math.round(dest.width() * k));
            int ph = Math.max(1, Math.round(dest.height() * k));
            while ((long) pw * ph > 9_000_000L) {
                pw = pw * 3 / 4;
                ph = ph * 3 / 4;
            }
            Bitmap b = Bitmap.createBitmap(Math.max(1, pw), Math.max(1, ph), Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.WHITE);
            Matrix m = new Matrix();
            m.setScale(pw / w, ph / h);
            PdfRenderer.Page page = renderer.openPage(index);
            try {
                page.render(b, null, m, preview ? PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        : PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
            } finally {
                page.close();
            }
            cache = b;
        }

        @Override
        public void draw(Bitmap strip, Canvas canvas, int y0, RectF dest, RectF box, float dpi, boolean preview) {
            if (direct) {
                ensureSize();
                float s = dest.width() / w;
                Matrix m = new Matrix();
                m.setScale(s, s);
                m.postTranslate(dest.left, dest.top - y0);
                PdfRenderer.Page page = renderer.openPage(index);
                try {
                    page.render(strip, null, m, preview ? PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            : PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                } finally {
                    page.close();
                }
                // Anything that spilled outside the printable box is painted over.
                Paint white = new Paint();
                white.setColor(Color.WHITE);
                white.setStyle(Paint.Style.FILL);
                RectF b = shift(box, y0);
                float sw = strip.getWidth();
                float sh = strip.getHeight();
                canvas.drawRect(0, 0, sw, Math.max(0f, b.top), white);
                canvas.drawRect(0, Math.min(sh, b.bottom), sw, sh, white);
                canvas.drawRect(0, 0, Math.max(0f, b.left), sh, white);
                canvas.drawRect(Math.min(sw, b.right), 0, sw, sh, white);
            } else if (cache != null) {
                canvas.save();
                canvas.clipRect(shift(box, y0));
                Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                canvas.drawBitmap(cache, null, shift(dest, y0), p);
                canvas.restore();
            }
        }

        @Override
        public void release() {
            if (cache != null) {
                cache.recycle();
                cache = null;
            }
        }
    }

    /** A photo. Decoded lazily, rotated by EXIF plus the user's rotation. */
    public static final class Img extends Source {
        private static final float ASSUMED_DPI = 300f;
        private final ContentResolver resolver;
        private final Uri uri;
        private final int degrees;
        private final int bw;
        private final int bh;
        private Bitmap bmp;

        public Img(Context ctx, Uri uri, int extraDegrees) {
            this.resolver = ctx.getContentResolver();
            this.uri = uri;
            int exif = 0;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in != null) {
                    ExifInterface e = new ExifInterface(in);
                    int o = e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                    if (o == ExifInterface.ORIENTATION_ROTATE_90) exif = 90;
                    else if (o == ExifInterface.ORIENTATION_ROTATE_180) exif = 180;
                    else if (o == ExifInterface.ORIENTATION_ROTATE_270) exif = 270;
                }
            } catch (IOException | RuntimeException ignored) {
                // no EXIF is fine
            }
            degrees = (exif + extraDegrees) % 360;
            int rawW = 1;
            int rawH = 1;
            try (InputStream in = resolver.openInputStream(uri)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(in, null, o);
                if (o.outWidth > 0 && o.outHeight > 0) {
                    rawW = o.outWidth;
                    rawH = o.outHeight;
                }
            } catch (IOException | RuntimeException ignored) {
                // handled when drawing
            }
            boolean swap = degrees == 90 || degrees == 270;
            bw = swap ? rawH : rawW;
            bh = swap ? rawW : rawH;
        }

        @Override public float widthMm() { return bw / ASSUMED_DPI * 25.4f; }

        @Override public float heightMm() { return bh / ASSUMED_DPI * 25.4f; }

        @Override
        public void prepare(RectF dest, float dpi, boolean preview) throws IOException {
            if (bmp != null) return;
            int maxSide = preview ? 1600 : 3200;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap decoded;
            try (InputStream in = resolver.openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
            if (decoded == null) throw new IOException("That picture could not be read");
            if (degrees != 0) {
                Matrix m = new Matrix();
                m.postRotate(degrees);
                Bitmap turned = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), m, true);
                if (turned != decoded) decoded.recycle();
                decoded = turned;
            }
            bmp = decoded;
        }

        @Override
        public void draw(Bitmap strip, Canvas canvas, int y0, RectF dest, RectF box, float dpi, boolean preview) {
            if (bmp == null) return;
            canvas.save();
            canvas.clipRect(shift(box, y0));
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            canvas.drawBitmap(bmp, null, shift(dest, y0), p);
            canvas.restore();
        }

        @Override
        public void release() {
            if (bmp != null) {
                bmp.recycle();
                bmp = null;
            }
        }
    }

    /** One page of laid-out text, drawn as vector so it stays sharp at 600 DPI. */
    public static final class Text extends Source {
        private final StaticLayout layout;
        private final int startLine;
        private final int endLine;
        private final float pageW;
        private final float pageH;
        private final float margin;

        public Text(StaticLayout layout, int startLine, int endLine, float pageW, float pageH, float margin) {
            this.layout = layout;
            this.startLine = startLine;
            this.endLine = endLine;
            this.pageW = pageW;
            this.pageH = pageH;
            this.margin = margin;
        }

        @Override public float widthMm() { return pageW / 72f * 25.4f; }

        @Override public float heightMm() { return pageH / 72f * 25.4f; }

        @Override
        public void draw(Bitmap strip, Canvas canvas, int y0, RectF dest, RectF box, float dpi, boolean preview) {
            canvas.save();
            canvas.clipRect(shift(box, y0));
            canvas.translate(dest.left, dest.top - y0);
            float s = dest.width() / pageW;
            canvas.scale(s, s);
            if (layout.getLineCount() > 0 && endLine > startLine) {
                float top = layout.getLineTop(startLine);
                canvas.translate(margin, margin - top);
                canvas.clipRect(0, top, layout.getWidth(), layout.getLineBottom(endLine - 1));
                layout.draw(canvas);
            }
            canvas.restore();
        }
    }

    /** Built-in printer test page. */
    public static final class Test extends Source {
        private final float pageW;
        private final float pageH;
        private final String paperLabel;

        public Test(float pageW, float pageH, String paperLabel) {
            this.pageW = pageW;
            this.pageH = pageH;
            this.paperLabel = paperLabel;
        }

        @Override public float widthMm() { return pageW / 72f * 25.4f; }

        @Override public float heightMm() { return pageH / 72f * 25.4f; }

        @Override
        public void draw(Bitmap strip, Canvas canvas, int y0, RectF dest, RectF box, float dpi, boolean preview) {
            canvas.save();
            canvas.clipRect(shift(box, y0));
            canvas.translate(dest.left, dest.top - y0);
            float s = dest.width() / pageW;
            canvas.scale(s, s);
            drawTest(canvas, pageW, pageH, paperLabel);
            canvas.restore();
        }

        private static void drawTest(Canvas canvas, float w, float h, String paperLabel) {
            float m = w * 0.08f;
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(Color.BLACK);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1.2f);
            canvas.drawRect(m, m, w - m, h - m, line);

            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.BLACK);
            fill.setStyle(Paint.Style.FILL);

            Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
            title.setColor(Color.BLACK);
            title.setFakeBoldText(true);
            title.setTextSize(w * 0.075f);
            float x = m * 1.6f;
            canvas.drawText("Noko Print", x, h * 0.20f, title);

            Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
            body.setColor(Color.BLACK);
            body.setTextSize(w * 0.034f);
            canvas.drawText("HP LaserJet M1005 MFP", x, h * 0.26f, body);
            canvas.drawText(paperLabel + "  ·  600 dpi  ·  black and white", x, h * 0.30f, body);
            canvas.drawLine(x, h * 0.33f, w - x, h * 0.33f, line);
            canvas.drawText("If you can read this, your phone is", x, h * 0.38f, body);
            canvas.drawText("printing through the USB cable.", x, h * 0.42f, body);

            // Grey ramp: shows how the printer renders tones.
            float bx = x;
            float bw = (w - 2 * x) / 10f;
            float by = h * 0.50f;
            float bh = h * 0.06f;
            for (int i = 0; i < 10; i++) {
                int v = 255 - i * 255 / 9;
                Paint p = new Paint();
                p.setColor(Color.rgb(v, v, v));
                canvas.drawRect(bx + i * bw, by, bx + (i + 1) * bw, by + bh, p);
            }
            canvas.drawRect(bx, by, bx + 10 * bw, by + bh, line);
            body.setTextSize(w * 0.026f);
            canvas.drawText("Tone ramp (white to black)", x, by + bh + h * 0.03f, body);

            // Fine lines and corner marks: check sharpness and edge clipping.
            float ly = h * 0.68f;
            for (int i = 0; i < 12; i++) {
                line.setStrokeWidth(0.3f + i * 0.2f);
                canvas.drawLine(x, ly + i * 7f, w - x, ly + i * 7f, line);
            }
            line.setStrokeWidth(2f);
            float c = w * 0.04f;
            canvas.drawLine(0, 0, c, 0, line);
            canvas.drawLine(0, 0, 0, c, line);
            canvas.drawLine(w, 0, w - c, 0, line);
            canvas.drawLine(w, 0, w, c, line);
            canvas.drawLine(0, h, c, h, line);
            canvas.drawLine(0, h, 0, h - c, line);
            canvas.drawLine(w, h, w - c, h, line);
            canvas.drawLine(w, h, w, h - c, line);
        }
    }

    /** Build a text paint used by pagination (kept here so size logic is in one place). */
    public static TextPaint textPaint(float sizePt) {
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        tp.setColor(Color.BLACK);
        tp.setTextSize(sizePt);
        return tp;
    }
}
