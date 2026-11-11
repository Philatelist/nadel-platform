# files

Checks and cleaning of uploaded files, run before anything is stored. No tables, no endpoints, no HTTP semantics and no
dependencies on other modules. Moved out of `treasury.internal` by feature 021, so the expense receipts (`treasury`)
and the meter photos (`meters`) share one implementation (JDK only, no image library).

| Type | What it is |
|---|---|
| `FileKind` | `JPEG`, `PNG`, `PDF`, each with the media type it is stored and served with (`mediaType()`) |
| `FileSniffer` | `sniff(content)`: the type by the first bytes (`FF D8 FF`, the PNG signature, `%PDF-`), never by the name or the declared type; empty for anything else |
| `ImageMetadata` | `strip(kind, content)`: a JPEG or PNG without its metadata and without re-encoding; a PDF is returned as received |
| `MalformedImageException` | A JPEG or PNG whose structure cannot be read; a fixed English message that never quotes the file |
| `UploadLimits` | `MAX_FILE_BYTES`: 5 MiB, the largest stored file |
| `internal.JpegMetadataStripper` | Keeps APP0, APP14, APP2 `ICC_PROFILE`, the tables, frames and every scan; drops APP1 (Exif with GPS and camera IFDs, XMP), other APP2 (MPF, FlashPix), APP3–APP13, APP15 and COM; re-adds a minimal APP1 Exif holding only the Orientation (1–8); cuts after the first EOI |
| `internal.PngMetadataStripper` | Keeps IHDR, PLTE, IDAT, IEND, tRNS, gAMA, cHRM, sRGB, iCCP, sBIT, bKGD, pHYs, acTL, fcTL, fdAT byte for byte; drops every other ancillary chunk; cuts after IEND; a wrong CRC or an unknown critical chunk is refused |

Each caller decides which kinds it accepts and maps a refusal to its own problem code and field: `treasury`
(`AttachmentUploads`) answers 413 `FILE_TOO_LARGE` and 400 `FILE_TYPE_NOT_ALLOWED` on `files[i]` and accepts PDF.

Tests: `FileSnifferTests`, `internal.JpegMetadataStripperTests`, `internal.PngMetadataStripperTests`, with the in-memory
camera fixtures of `TestImages` (test sources, shared with the callers' tests).
