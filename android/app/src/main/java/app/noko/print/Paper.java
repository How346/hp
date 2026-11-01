package app.noko.print;

/** Paper sizes the HP M1005 accepts. Codes are Windows DEVMODE paper codes. */
public enum Paper {
    A4("A4", 9, 210.0, 297.0),
    LETTER("Letter", 1, 215.9, 279.4),
    LEGAL("Legal", 5, 215.9, 355.6),
    A5("A5", 11, 148.0, 210.0),
    B5("B5", 13, 182.0, 257.0),
    EXECUTIVE("Executive", 7, 184.15, 266.7);

    public static final int DPI = 600;

    public final String label;
    public final int code;
    public final double widthMm;
    public final double heightMm;

    Paper(String label, int code, double widthMm, double heightMm) {
        this.label = label;
        this.code = code;
        this.widthMm = widthMm;
        this.heightMm = heightMm;
    }

    /** Sheet width in mm for the given reading orientation. */
    public float sheetWidthMm(boolean landscape) {
        return (float) (landscape ? heightMm : widthMm);
    }

    /** Sheet height in mm for the given reading orientation. */
    public float sheetHeightMm(boolean landscape) {
        return (float) (landscape ? widthMm : heightMm);
    }

    /** Width in pixels of the sheet as the user reads it, at 600 DPI. */
    public int sheetWidthPx(boolean landscape) {
        return (int) Math.round(sheetWidthMm(landscape) / 25.4 * DPI);
    }

    /** Height in pixels of the sheet as the user reads it, at 600 DPI. */
    public int sheetHeightPx(boolean landscape) {
        return (int) Math.round(sheetHeightMm(landscape) / 25.4 * DPI);
    }

    /** The printer always receives a portrait raster. */
    public int rasterWidthPx() {
        return (int) Math.round(widthMm / 25.4 * DPI);
    }

    public int rasterHeightPx() {
        return (int) Math.round(heightMm / 25.4 * DPI);
    }

    public static int stride(int width) {
        int bpl = (width + 7) / 8;
        return (bpl + 15) & ~15;
    }

    @Override
    public String toString() {
        return label;
    }
}
