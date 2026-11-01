package app.noko.print;

import java.util.LinkedHashSet;
import java.util.Set;

/** Parses page ranges such as "1-3, 5, 8-" into zero-based page indexes. */
public final class PageSpec {
    private PageSpec() {}

    public static int[] parse(String spec, int total) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter pages like 1-3, 5, 8-");
        }
        Set<Integer> out = new LinkedHashSet<>();
        for (String raw : spec.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) continue;
            try {
                int dash = part.indexOf('-');
                int from;
                int to;
                if (dash < 0) {
                    from = Integer.parseInt(part);
                    to = from;
                } else {
                    String a = part.substring(0, dash).trim();
                    String b = part.substring(dash + 1).trim();
                    from = a.isEmpty() ? 1 : Integer.parseInt(a);
                    to = b.isEmpty() ? total : Integer.parseInt(b);
                }
                if (from < 1 || to < from || to > total) {
                    throw new IllegalArgumentException("Pages must be between 1 and " + total);
                }
                for (int p = from; p <= to; p++) out.add(p - 1);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("\"" + part + "\" is not a valid page range");
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("No pages selected");
        int[] result = new int[out.size()];
        int i = 0;
        for (int v : out) result[i++] = v;
        return result;
    }
}
