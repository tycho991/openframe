# QR Code generator (vendored)

`QrCode.java`, `QrSegment.java`, `BitBuffer.java` and `DataTooLongException.java` are copied
unchanged from Project Nayuki's QR Code generator library, version 1.8.0
(`io.nayuki:qrcodegen:1.8.0`, sources jar SHA-1 `8367fa543dcaeb0b89a924cebe43b70f48615820`),
<https://www.nayuki.io/page/qr-code-generator-library>. MIT License, see the header of each file.

Why copied instead of a dependency: `:core` runs on Android from API 21, and the published jar
contains `QrSegmentAdvanced`, which calls `CharSequence.chars()`, `codePoints()` and
`IntStream` (API 24). Animal Sniffer checks only the project's own classes, so it would not see
those calls in a jar. The four files used here call no API that is missing on API 21, and
`./gradlew :core:check` verifies that. `QrSegmentAdvanced` and `module-info.java` are left out.

Use it only through `io.github.tycho991.openframe.core.qr.QrMatrix`.
