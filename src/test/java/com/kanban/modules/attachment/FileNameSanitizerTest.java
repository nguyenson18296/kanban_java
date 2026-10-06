package com.kanban.modules.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FileNameSanitizerTest {
  @Test
  @DisplayName("ordinary names, including non-ASCII ones, pass through unchanged")
  void keepsOrdinaryNames() {
    assertThat(FileNameSanitizer.sanitize("login-bug.png")).isEqualTo("login-bug.png");
    assertThat(FileNameSanitizer.sanitize("ảnh chụp màn hình.png")).isEqualTo("ảnh chụp màn hình.png");
  }

  @Test
  @DisplayName("directory parts are dropped (browser fakepath, ../ traversal, nested folders)")
  void stripsDirectories() {
    assertThat(FileNameSanitizer.sanitize("C:\\fakepath\\shot.png")).isEqualTo("shot.png");
    assertThat(FileNameSanitizer.sanitize("../../etc/passwd")).isEqualTo("passwd");
    assertThat(FileNameSanitizer.sanitize("folder/sub/report.pdf")).isEqualTo("report.pdf");
  }

  @Test
  @DisplayName("control and invisible format characters are removed (U+202E cannot flip the extension)")
  void removesControlAndInvisibleCharacters() {
    assertThat(FileNameSanitizer.sanitize("shot\u0000\n.png")).isEqualTo("shot.png");
    assertThat(FileNameSanitizer.sanitize("invoice\u202Efdp.exe")).isEqualTo("invoicefdp.exe");
  }

  @Test
  @DisplayName("nothing usable left → 'attachment'")
  void fallsBackWhenNothingIsLeft() {
    assertThat(FileNameSanitizer.sanitize(null)).isEqualTo("attachment");
    assertThat(FileNameSanitizer.sanitize("")).isEqualTo("attachment");
    assertThat(FileNameSanitizer.sanitize("   ")).isEqualTo("attachment");
    assertThat(FileNameSanitizer.sanitize(".")).isEqualTo("attachment");
    assertThat(FileNameSanitizer.sanitize("..")).isEqualTo("attachment");
    assertThat(FileNameSanitizer.sanitize("dir/")).isEqualTo("attachment");
    assertThat(FileNameSanitizer.sanitize("\u202E")).isEqualTo("attachment");
  }

  @Test
  @DisplayName("long names are cut to 255 characters, keeping a short extension")
  void truncatesKeepingExtension() {
    String result = FileNameSanitizer.sanitize("a".repeat(300) + ".png");
    assertThat(result).hasSize(255).endsWith(".png");

    assertThat(FileNameSanitizer.sanitize("a".repeat(300))).hasSize(255);
    // a 41-char "extension" is not an extension worth keeping
    assertThat(FileNameSanitizer.sanitize("a".repeat(300) + "." + "b".repeat(40))).hasSize(255);
  }

  @Test
  @DisplayName("the cut never splits an emoji into a lone surrogate")
  void truncatesByCodePoint() {
    String result = FileNameSanitizer.sanitize("😀".repeat(300) + ".png");
    assertThat(result.codePointCount(0, result.length())).isEqualTo(255);
    assertThat(result).endsWith(".png");
    assertThat(result.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isTrue();
  }

  @Test
  @DisplayName("an extension that does not match the detected type gets the type's extension appended")
  void appendsDetectedExtension() {
    assertThat(FileNameSanitizer.withExtensionOf("report.bat", AllowedFileType.PDF)).isEqualTo("report.bat.pdf");
    assertThat(FileNameSanitizer.withExtensionOf("x.html", AllowedFileType.GIF)).isEqualTo("x.html.gif");
    assertThat(FileNameSanitizer.withExtensionOf("report.pdf", AllowedFileType.PNG)).isEqualTo("report.pdf.png");
    assertThat(FileNameSanitizer.withExtensionOf("shot", AllowedFileType.PNG)).isEqualTo("shot.png");
    assertThat(FileNameSanitizer.withExtensionOf("attachment", AllowedFileType.PDF)).isEqualTo("attachment.pdf");
  }

  @Test
  @DisplayName("a matching extension is kept as sent, in any case and any of the type's spellings")
  void keepsMatchingExtension() {
    assertThat(FileNameSanitizer.withExtensionOf("photo.JPG", AllowedFileType.JPEG)).isEqualTo("photo.JPG");
    assertThat(FileNameSanitizer.withExtensionOf("photo.jpeg", AllowedFileType.JPEG)).isEqualTo("photo.jpeg");
    assertThat(FileNameSanitizer.withExtensionOf("ảnh chụp.png", AllowedFileType.PNG)).isEqualTo("ảnh chụp.png");
    assertThat(FileNameSanitizer.withExtensionOf("scan.Pdf", AllowedFileType.PDF)).isEqualTo("scan.Pdf");
  }

  @Test
  @DisplayName("appending never pushes a name past 255 characters")
  void appendKeepsLengthLimit() {
    String result = FileNameSanitizer.withExtensionOf("a".repeat(251) + ".bat", AllowedFileType.PDF);
    assertThat(result).hasSize(255).endsWith(".pdf");
  }
}
