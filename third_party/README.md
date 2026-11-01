# Native audio dependency

`opus-1.5.2.tar.gz` is the unmodified upstream Opus 1.5.2 release from
https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz.

SHA-256: `65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1`.

The archive is included to make Android native builds independent of an upstream download during CMake configuration. CMake checks its hash before extracting it into the ignored build directory. The upstream source and copyright notices are preserved in the archive. The upstream `COPYING` is also distributed in the APK's `assets/licenses/opus-COPYING.txt`.
