# HP LaserJet M1005 OTG Print App

Android app skeleton for printing documents/PDFs via USB OTG to **HP LaserJet M1005** (VID: 0x03F0, PID: 0x3B17, serial example KJ73EDG).

## Important Notes

- The HP LaserJet M1005 is a **host-based** printer (not PCL/PostScript). Official HP Print Service Plugin does **not** support OTG printing for this model.
- Third-party apps like **NokoPrint** and the specialized **M1005 OTG** apps work by sending a proprietary host-based data stream over USB bulk transfers.
- This project provides a complete, buildable framework:
  - USB Host detection & permission for the exact VID/PID
  - PDF document picker
  - USB connection, interface claim, bulk OUT endpoint
  - Placeholder for sending print data

You must implement the actual print protocol (raster conversion + HP host-based command sequence) by reverse-engineering USB traffic from a working PC driver or NokoPrint, or by consulting open research on similar HP host-based printers.

## Features

- Detects and connects to HP LaserJet M1005 over OTG
- Pick any PDF
- Sends test data (replace with real protocol)
- Ready for GitHub Actions build

## How to Build

1. Clone / push this repo to GitHub.
2. The included `.github/workflows/android-build.yml` will automatically build the Debug APK on push/PR.
3. Download the APK artifact from the Actions tab.

Or locally with Android Studio / Gradle:

```bash
gradle assembleDebug
```

## Requirements

- Android device with USB OTG support
- OTG cable + standard USB printer cable
- HP LaserJet M1005 powered on

## Next Steps for Real Printing

1. Render PDF pages with `PdfRenderer` at 300 or 600 dpi.
2. Convert to 1-bit monochrome bitmap.
3. Encode into the specific HP LaserJet M1005 host-based packet format.
4. Send via `connection.bulkTransfer(...)`.

See `MainActivity.java` for the TODO comments and USB handling code.

Serial number example from user: KJ73EDG (not required in app).
