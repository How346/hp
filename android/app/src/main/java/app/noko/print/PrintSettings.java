package app.noko.print;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** All user-adjustable print options. Persisted between launches. */
public final class PrintSettings {
    public static final int[] NUP = {1, 2, 4, 6, 9};
    public static final int[] TEXT_SIZES = {10, 12, 14};

    public Paper paper = Paper.A4;
    /** 0 auto, 1 portrait, 2 landscape */
    public int orientation = 0;
    /** 0 fit, 1 fill (crop), 2 actual size */
    public int scaling = 0;
    /** 0 none, 1 narrow, 2 normal, 3 wide */
    public int margin = 1;
    /** 0 center, 1 top */
    public int position = 0;
    public int nupIndex = 0;
    public int copies = 1;
    public boolean collate = true;
    /** 0 all, 1 odd, 2 even, 3 custom */
    public int pageMode = 0;
    public String range = "";
    public boolean reverse = false;
    public boolean manualDuplex = false;
    public boolean reverseEven = false;
    public int density = 3;
    public boolean toner = false;
    /** 0 auto, 1 text (sharp), 2 photo (halftone) */
    public int tone = 0;
    public int brightness = 0;
    public int contrast = 100;
    public boolean pageNumbers = false;
    public boolean border = false;
    public int textSizeIndex = 1;
    /** quarter turns applied to photos */
    public int rotation = 0;
    public boolean grayPreview = false;

    public PrintSettings() {
        String country = Locale.getDefault().getCountry();
        if ("US".equals(country) || "CA".equals(country) || "MX".equals(country)) {
            paper = Paper.LETTER;
        }
    }

    public PrintSettings copy() {
        PrintSettings c = new PrintSettings();
        c.paper = paper;
        c.orientation = orientation;
        c.scaling = scaling;
        c.margin = margin;
        c.position = position;
        c.nupIndex = nupIndex;
        c.copies = copies;
        c.collate = collate;
        c.pageMode = pageMode;
        c.range = range;
        c.reverse = reverse;
        c.manualDuplex = manualDuplex;
        c.reverseEven = reverseEven;
        c.density = density;
        c.toner = toner;
        c.tone = tone;
        c.brightness = brightness;
        c.contrast = contrast;
        c.pageNumbers = pageNumbers;
        c.border = border;
        c.textSizeIndex = textSizeIndex;
        c.rotation = rotation;
        c.grayPreview = grayPreview;
        return c;
    }

    public int nup() {
        return NUP[Math.max(0, Math.min(NUP.length - 1, nupIndex))];
    }

    public int textSize() {
        return TEXT_SIZES[Math.max(0, Math.min(TEXT_SIZES.length - 1, textSizeIndex))];
    }

    public float marginMm() {
        switch (margin) {
            case 0: return 0f;
            case 1: return 5f;
            case 2: return 10f;
            default: return 20f;
        }
    }

    private static final String FILE = "noko_print_settings";

    public static PrintSettings load(Context ctx) {
        PrintSettings s = new PrintSettings();
        SharedPreferences p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Paper[] papers = Paper.values();
        int paperIndex = p.getInt("paper", s.paper.ordinal());
        if (paperIndex >= 0 && paperIndex < papers.length) s.paper = papers[paperIndex];
        s.orientation = clamp(p.getInt("orientation", s.orientation), 0, 2);
        s.scaling = clamp(p.getInt("scaling", s.scaling), 0, 2);
        s.margin = clamp(p.getInt("margin", s.margin), 0, 3);
        s.position = clamp(p.getInt("position", s.position), 0, 1);
        s.nupIndex = clamp(p.getInt("nup", s.nupIndex), 0, NUP.length - 1);
        s.collate = p.getBoolean("collate", s.collate);
        s.reverse = p.getBoolean("reverse", s.reverse);
        s.manualDuplex = p.getBoolean("duplex", s.manualDuplex);
        s.reverseEven = p.getBoolean("reverseEven", s.reverseEven);
        s.density = clamp(p.getInt("density", s.density), 1, 5);
        s.toner = p.getBoolean("toner", s.toner);
        s.tone = clamp(p.getInt("tone", s.tone), 0, 2);
        s.brightness = clamp(p.getInt("brightness", s.brightness), -50, 50);
        s.contrast = clamp(p.getInt("contrast", s.contrast), 50, 150);
        s.pageNumbers = p.getBoolean("pageNumbers", s.pageNumbers);
        s.border = p.getBoolean("border", s.border);
        s.textSizeIndex = clamp(p.getInt("textSize", s.textSizeIndex), 0, TEXT_SIZES.length - 1);
        s.grayPreview = p.getBoolean("grayPreview", s.grayPreview);
        return s;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("paper", paper.ordinal())
                .putInt("orientation", orientation)
                .putInt("scaling", scaling)
                .putInt("margin", margin)
                .putInt("position", position)
                .putInt("nup", nupIndex)
                .putBoolean("collate", collate)
                .putBoolean("reverse", reverse)
                .putBoolean("duplex", manualDuplex)
                .putBoolean("reverseEven", reverseEven)
                .putInt("density", density)
                .putBoolean("toner", toner)
                .putInt("tone", tone)
                .putInt("brightness", brightness)
                .putInt("contrast", contrast)
                .putBoolean("pageNumbers", pageNumbers)
                .putBoolean("border", border)
                .putInt("textSize", textSizeIndex)
                .putBoolean("grayPreview", grayPreview)
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
