# Noko Print Studio 1.2.0

## Print workspace updates
- Paper size: A4, A5, Letter and Legal.
- Portrait and landscape orientation.
- PDF page range, multiple copies and collated/uncollated copy order.
- Scaling: Fit to page, Fill page (crop edges), and Fit width.
- Margins: none, narrow, normal and wide.
- Position: center, top or bottom.
- Five-step print darkness and toner-saver control.
- Grayscale PDF preview; the M1005 output remains monochrome.
- Monochrome halftone conversion uses a 4x4 ordered dither so light grey fills,
  shaded cells and anti-aliased edges are represented on the printer's 1-bit engine.

## USB stability
- Repeated permission callbacks for an already-open device are now idempotent.
- The app avoids closing/reopening a healthy link when the same device reports
  permission more than once.
- Connection labels are only updated when the state actually changes.
- The existing USB detach handling and serialized print worker are retained.

## Build
GitHub Actions uses Java 17 and Gradle 8.7 and builds `android/app` with
`gradle assembleDebug`. Run the `Build Noko Print` workflow and download the
`noko-print` artifact.

## Important hardware notes
- This project targets the HP LaserJet M1005 USB OTG path.
- The printer is monochrome; colour source documents are rendered as
  monochrome halftones.
- This environment did not have the Android SDK or Gradle installed, so a
  complete APK build and physical print test could not be performed here.
  GitHub Actions must confirm Android compilation, followed by a real printer
  test for each paper/scale option.
