# Noko Print — print clarity and preview update

## Changes in this update

- Removed ordered Bayer dithering from the 1-bit raster conversion. The previous pattern could make solid text and fine lines look dotted or show white pinholes. The converter now uses a clean monochrome threshold to produce continuous black strokes on white paper.
- Increased the on-screen preview render size to 1200 px for better inspection.
- Tapping the preview opens a larger viewer. Pinch to zoom (up to 6×) and drag while zoomed to inspect details.
- Increased the copies +/- button touch targets to 52 × 48 dp and retained explicit click handlers.
- Added an honest DOCX limitation notice: the current built-in DOCX reader extracts text and does not preserve Word page layout, fonts, tables or exact spacing. Export DOCX to PDF for layout-faithful printing.

## Important print limitations

- HP LaserJet M1005 is monochrome; colour is necessarily converted to black and white.
- A 1-bit printer raster cannot reproduce colour or continuous grayscale. This update prioritizes clean text and line art over simulated gray halftone dots. Some light-gray fills may become white.
- Exact physical output also depends on toner, paper, printer condition and the source PDF. A real print test is required.
- DOCX is not yet rendered by a full Word-compatible layout engine. Use PDF for same-layout printing.
- The zoom viewer magnifies the generated on-screen preview; it does not change print resolution or print settings.

## Build verification

The Android project in this archive does not include a Gradle wrapper. Run the existing GitHub Actions workflow to compile it, then test with the actual HP LaserJet M1005 over OTG. No successful build or physical print is claimed here.
