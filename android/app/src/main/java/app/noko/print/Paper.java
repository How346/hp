package app.noko.print;

public enum Paper {
    A4("A4", 9, 210.0, 297.0),
    LETTER("Letter", 1, 215.9, 279.4),
    LEGAL("Legal", 5, 215.9, 355.6),
    A5("A5", 11, 148.0, 210.0);

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

    public int widthPx() {
        return (int) Math.round(widthMm / 25.4 * DPI);
    }

    public int heightPx() {
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
