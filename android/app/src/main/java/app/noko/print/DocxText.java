package app.noko.print;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Pulls the plain text out of a .docx file (formatting and images are not kept). */
public final class DocxText {
    private DocxText() {}

    public static String extract(InputStream raw) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    return parse(zip);
                }
            }
        }
        throw new IOException("This does not look like a Word document");
    }

    private static String parse(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        try {
            XmlPullParser xp = Xml.newPullParser();
            xp.setInput(in, "UTF-8");
            boolean inText = false;
            int event = xp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT && out.length() < 400_000) {
                if (event == XmlPullParser.START_TAG) {
                    String n = xp.getName();
                    if ("w:t".equals(n)) inText = true;
                    else if ("w:tab".equals(n)) out.append('\t');
                    else if ("w:br".equals(n)) out.append('\n');
                } else if (event == XmlPullParser.TEXT) {
                    if (inText) out.append(xp.getText());
                } else if (event == XmlPullParser.END_TAG) {
                    String n = xp.getName();
                    if ("w:t".equals(n)) inText = false;
                    else if ("w:p".equals(n)) out.append('\n');
                }
                event = xp.next();
            }
        } catch (XmlPullParserException e) {
            throw new IOException("Could not read that Word document");
        }
        return out.toString();
    }
}
