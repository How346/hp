package app.noko.print;

import java.io.IOException;

public final class XqxEncoder {
    static {
        System.loadLibrary("nokoxqx");
    }

    private XqxEncoder() {}

    public static byte[] encode(
            byte[] bits,
            int width,
            int height,
            int stride,
            int resX,
            int resY,
            int paper,
            int density,
            int econo,
            int copies) throws IOException {
        byte[] out = nativeEncode(bits, width, height, stride, resX, resY, paper, density, econo, copies);
        if (out == null) {
            throw new IOException("Could not build a page for this printer");
        }
        return out;
    }

    private static native byte[] nativeEncode(
            byte[] bits,
            int width,
            int height,
            int stride,
            int resX,
            int resY,
            int paper,
            int density,
            int econo,
            int copies);
}
