package app.noko.print;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** What the user chose to print. */
public final class Document {
    public enum Kind { PDF, IMAGES, TEXT, TEST }

    public final Kind kind;
    public final String name;
    public final List<Uri> uris = new ArrayList<>();
    public final String text;
    /** Pages for PDFs, photos for image sets, 0 when unknown. */
    public final int count;

    public Document(Kind kind, String name, List<Uri> uris, String text, int count) {
        this.kind = kind;
        this.name = name;
        if (uris != null) this.uris.addAll(uris);
        this.text = text;
        this.count = count;
    }

    public boolean isPhotoContent() { return kind == Kind.IMAGES; }

    public static String nameOf(ContentResolver cr, Uri uri) {
        try (Cursor cursor = cr.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = cursor.getString(idx);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        String last = uri.getLastPathSegment();
        return last == null ? "Document" : last;
    }

    /** Returns null for unsupported types. */
    public static Kind detect(ContentResolver cr, Uri uri, String name) {
        String type = null;
        try {
            type = cr.getType(uri);
        } catch (RuntimeException ignored) {
            // use the name instead
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if ((type != null && type.equalsIgnoreCase("application/pdf")) || n.endsWith(".pdf")) return Kind.PDF;
        if ((type != null && type.startsWith("image/"))
                || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".webp") || n.endsWith(".bmp") || n.endsWith(".gif")) return Kind.IMAGES;
        if (isDocx(type, n)) return Kind.TEXT;
        if ((type != null && type.startsWith("text/"))
                || n.endsWith(".txt") || n.endsWith(".csv") || n.endsWith(".log") || n.endsWith(".md")) return Kind.TEXT;
        return null;
    }

    public static boolean isDocx(String type, String lowerName) {
        return (type != null && type.contains("wordprocessingml")) || lowerName.endsWith(".docx");
    }
}
