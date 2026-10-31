# Noko Print Studio 1.1.0

## Included improvements
- Refreshed Android print workspace with a document preview and clearer settings sections.
- USB connection state handling with attach/detach monitoring, duplicate-connect protection, and stale-link recovery.
- Portrait and landscape page orientation.
- A4, Letter, Legal, and A5 paper selections.
- PDF page-range printing, 1–20 copies, five print-darkness levels, and toner-saver mode.
- Black-and-white preview and 600 DPI raster output.
- PDF, common image formats, and text-file printing.
- Preserves the HP LaserJet M1005 USB OTG print pipeline.

## Important printer limitations
- HP LaserJet M1005 is a monochrome printer, so output is black and white.
- Automatic duplex printing is not exposed by this USB raster workflow; use manual duplex by printing odd and even page ranges separately and reloading the paper as directed by the printer.
- DOC/DOCX files are not directly rendered. Export them to PDF before printing.
- Paper sizes offered are common sizes; the printer's tray and driver/firmware still determine which media it can physically accept.

## Build
The GitHub Actions workflow installs Gradle 8.7 and Android SDK components, then runs `gradle assembleDebug` from `android/`. Download the `noko-print` artifact after a successful workflow run.
