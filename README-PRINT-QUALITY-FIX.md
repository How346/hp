# Noko Print — PDF print-quality update

This update changes the PDF/image-to-monochrome conversion used by the HP LaserJet M1005.

## What changed

- Replaced a single hard black/white cutoff with Floyd–Steinberg error-diffusion halftoning.
- Light-grey fills, shaded table cells, gradients and anti-aliased PDF edges are converted into printable dot patterns instead of being discarded as white.
- Transparent pixels are composited onto white before monochrome conversion, avoiding unexpected dark/black transparent areas.
- The same conversion is applied across strip boundaries so halftone texture does not restart every strip.
- The printer still receives its native 1-bit monochrome raster; no unsupported colour mode is sent to the M1005.

## Important limitation

The HP LaserJet M1005 is a monochrome printer. Colour PDFs cannot print in colour; colours are converted to grayscale and then halftoned. “Same to same” is therefore limited by monochrome output, printer resolution, toner and paper. Very pale fills may remain subtle.

## Build

Run the repository's existing GitHub Actions workflow. A physical test print is still needed to confirm output on the target M1005 and Android phone.
