# Noko Print Studio 2.1.0

Native Android app that prints straight to an **HP LaserJet M1005** over USB OTG.

## What's new in 2.0

**Connection fix (no more connected / not connected flicker)**
One state machine (`PrinterConnection`) owns the link. It never polls, ignores duplicate
USB events, only leaves "Connected" on a real unplug or failed transfer, and only updates
the screen when something actually changed. It also reconnects on plug-in and after one
failed transfer, and keeps the link when you switch apps.

**Landscape fix**
The printer needs a portrait raster. Landscape pages are now rotated into one before
sending (the old version sent a sideways raster).

**Screens**
Home (printer status, Documents / Photos / Type or paste / Test page, Recent files) and a
Preview screen with sheet navigation, B&W / grayscale preview, copies, and Print.

**Settings (bottom sheet)**
Paper A4, Letter, Legal, A5, B5, Executive · Orientation auto / portrait / landscape ·
Pages all / odd / even / custom range ("1-3, 5, 8-") · reverse order · manual double-sided
(odd pages, flip prompt, even pages) · pages per sheet 1/2/4/6/9 · scaling fit / fill / actual ·
margins · position · sheet numbers · borders · text mode vs photo halftone · darkness ·
brightness · contrast · toner saver · copies with collate · text size · photo rotation.

**Files**
PDF, JPG/PNG/WebP/BMP/GIF (multi-select, EXIF-aware), TXT/CSV/LOG/MD, DOCX (text only).
Open or share files from other apps straight into Noko Print. Dark mode. Landscape phones.

## Limits
- Monochrome printer: colour is converted to grey.
- No automatic duplex or scanning over this USB raster path.
- DOCX prints text only (no images/formatting); export to PDF for exact layout.
- Not an Android system print service, so other apps use Share / Open with.

## Build
Push to GitHub: the workflow builds `app-debug.apk` (artifact `noko-print`).
Or locally: `cd android && gradle assembleDebug` (JDK 17, Android SDK 34, NDK 26.1, CMake 3.22).

## 2.1.0 fixes
- Settings sheet scrolls smoothly and can no longer close by accident (close with Done, X or back).
- Print reliability: auto reconnect and resend if the USB handle went stale, stuck-permission fix,
  job can never leave the app stuck, wake lock + screen kept on while printing, more memory for big photos.
- Failed prints show a clear dialog with Try again.
- Polish: swipe between sheets, fades, paper-edge preview, live "Print N sheets" button, Reset settings.
