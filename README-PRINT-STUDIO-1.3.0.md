# Noko Print Studio 1.3.0

## Updates
- Added a horizontally scrollable thumbnail strip for every page in a selected PDF. Tap any thumbnail to open that page in the large monochrome preview.
- Added current-page / total-pages preview status.
- Fixed copies +/- button contrast, glyph size, and button surface styling.
- Added DOCX file selection and text extraction for modern Word documents. DOCX documents are paginated across multiple printed sheets instead of being cut off at the first page.
- Improved TXT/CSV/Markdown pagination so long documents print across multiple sheets.
- Improved connection-state behavior: connection status changes only on confirmed attach/detach or transfer failure; a pending connection request no longer briefly displays a false disconnected state.
- Failed USB links are closed and released before reconnecting.

## Supported input
- PDF (all pages, page-range printing, page thumbnails)
- DOCX (text and paragraph content; complex Word layouts, embedded images, tables, and exact Word pagination are not preserved)
- TXT, CSV, MD, LOG, RTF-like plain text
- JPG, PNG, WEBP, BMP, GIF

Legacy binary `.doc` files are not supported; save them as `.docx` or PDF first.

## Build
GitHub Actions uses Java 17 and Gradle 8.7, then runs `gradle assembleDebug` from `android/`.

## Verification
XML files were parsed successfully and source structure checks passed. A complete Android SDK build and a physical USB printer test were not available in this environment; run the GitHub Actions workflow and test the resulting APK on the target phone/printer before relying on it.
