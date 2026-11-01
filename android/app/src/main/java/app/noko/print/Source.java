package app.noko.print;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import java.io.IOException;

/** One logical page of content (a PDF page, a photo, a page of text...). */
public abstract class Source {
    /** Natural printed size in millimetres. */
    public abstract float widthMm();

    public abstract float heightMm();

    /** Load heavy resources for one sheet. dest is in sheet pixels at dpi. */
    public void prepare(RectF dest, float dpi, boolean preview) throws IOException {}

    /**
     * Draw onto a horizontal strip of the sheet.
     * strip is the target bitmap, y0 the sheet row at the top of the strip.
     * dest and box are in sheet pixels at dpi (box clips the drawing).
     */
    public abstract void draw(Bitmap strip, Canvas canvas, int y0, RectF dest, RectF box, float dpi, boolean preview);

    public void release() {}
}
