# Additional archive libraries

- Junrar 7.5.5: https://github.com/junrar/junrar/tree/v7.5.5
  - Used only to read/extract RAR archives. Its code must not be used to develop a RAR (WinRAR) compatible archiver or recreate the RAR compression algorithm.
  - UnRAR copyright: Alexander Roshal. License included in `app/src/main/assets/licenses/junrar-LICENSE.txt` and the APK assets.
- Apache Commons Compress 1.28.0: https://commons.apache.org/proper/commons-compress/
  - Apache License 2.0. The dependency's `META-INF/LICENSE.txt` and `META-INF/NOTICE.txt` are merged into the APK.
- XZ for Java 1.10: https://github.com/tukaani-project/xz-java/tree/v1.10
  - Copyright the XZ for Java authors and contributors. Zero-Clause BSD license included in `app/src/main/assets/licenses/xz-COPYING.txt` and the APK assets.

The RAR fixture in `app/src/androidTest/assets/stored.rar` is generated from an original 1×1 image by `tests/create_rar_fixture.py`; it contains no third-party book content.
