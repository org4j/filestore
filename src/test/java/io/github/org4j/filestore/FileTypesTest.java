package io.github.org4j.filestore;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class FileTypesTest {

  @Test
  void theTypeComesFromTheBytesNotTheName() throws Exception {
    assertThat(FileTypes.detect(pdf())).contains("application/pdf");
    assertThat(FileTypes.detect(image("png"))).contains("image/png");
    assertThat(FileTypes.detect(image("jpg"))).contains("image/jpeg");
    assertThat(FileTypes.detect("<svg onload=alert(1)>".getBytes(StandardCharsets.UTF_8)))
        .isEmpty();
  }

  @Test
  void aFakeOrHalfFileHasNoType() throws Exception {
    var png = image("png");
    var truncated = java.util.Arrays.copyOf(png, 20);
    assertThat(FileTypes.detect(truncated)).as("PNG signature, not a PNG").isEmpty();
    assertThat(FileTypes.detect("%PDF-1.7 no end".getBytes(StandardCharsets.US_ASCII))).isEmpty();
    var polyglot = new ByteArrayOutputStream();
    polyglot.write(pdf());
    polyglot.write(new byte[] {'P', 'K', 5, 6, 0, 0});
    assertThat(FileTypes.detect(polyglot.toByteArray())).as("a PDF hiding a ZIP").isEmpty();
  }

  @Test
  void aSmallFileClaimingAHugeImageIsNoImageAndCostsNoMemory() throws Exception {
    var bomb = claiming(16_000, 16_000);
    assertThat(allocatedWhile(() -> assertThat(FileTypes.detect(bomb)).isEmpty()))
        .as("16000 x 16000 pixels decoded would take 768 MB")
        .isLessThan(64_000_000);
  }

  @Test
  void aLargeImageIsDecodedAtAFractionOfItsSize() throws Exception {
    var large = claiming(9_000, 9_000);
    assertThat(allocatedWhile(() -> FileTypes.detect(large)))
        .as("9000 x 9000 pixels decoded whole would take 243 MB")
        .isLessThan(64_000_000);
  }

  @Test
  void namesKeepOnlyTheirLastPartAndSafeCharacters() {
    assertThat(FileTypes.safeName("../../etc/passwd")).isEqualTo("passwd");
    assertThat(FileTypes.safeName("C:\\docs\\Ustav \"2026\".pdf")).isEqualTo("Ustav _2026_.pdf");
    assertThat(FileTypes.safeName("Устав\r\n.pdf")).isEqualTo("Устав__.pdf");
    assertThat(FileTypes.safeName(null)).isEqualTo("file");
  }

  static byte[] pdf() {
    return "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n"
        .getBytes(StandardCharsets.US_ASCII);
  }

  static byte[] image(String format) throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), format, out);
    return out.toByteArray();
  }

  /** A real 4 x 4 PNG whose header claims these dimensions. */
  static byte[] claiming(int width, int height) throws Exception {
    var png = image("png");
    ByteBuffer.wrap(png).putInt(16, width).putInt(20, height);
    var crc = new CRC32();
    crc.update(png, 12, 17);
    ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
    return png;
  }

  private static long allocatedWhile(Runnable work) {
    var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    var before = threads.getCurrentThreadAllocatedBytes();
    work.run();
    return threads.getCurrentThreadAllocatedBytes() - before;
  }
}
