# Noko Print Studio 1.3.3 – print rendering update

## Changes
- Replaced the previous fixed 176-level cutoff with continuous Floyd–Steinberg grayscale-to-monochrome conversion.
- Error diffusion is carried across the renderer's 64-row strips, preventing strip boundaries from creating visible bands.
- Near-white paper is normalized to white to reduce isolated background speckles; light gray content is retained for halftone reproduction.
- Removed the user-facing DPI/density tuning controls. The print engine retains its required internal 600-dpi raster dimensions and a fixed standard density setting.
- Preserved the existing HP XQX + JBIG + USB OTG data path.

## Important limitations
The HP LaserJet M1005 is a monochrome printer. It cannot reproduce color. This update improves grayscale tone reproduction and reduces lost light details, but a 1-bit printer engine cannot produce a mathematically identical copy of a color photo or PDF. Actual output still depends on toner, paper, printer condition, and hardware calibration.

## Verification
ZIP integrity and XML well-formedness were checked. This environment did not have an Android Gradle wrapper/SDK build setup or the physical HP LaserJet M1005 attached, so a successful APK build and physical print are not claimed. Run the included GitHub Actions workflow and print a grayscale test page to validate on-device.
