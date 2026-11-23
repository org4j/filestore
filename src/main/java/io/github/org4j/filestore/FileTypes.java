package io.github.org4j.filestore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.jspecify.annotations.Nullable;

/** What a file is, read from its bytes, and a name safe to keep and hand back. */
final class FileTypes {

  private FileTypes() {}

  static Optional<String> detect(byte[] bytes) {
    if (indexOf(bytes, FileStoreConstants.ZIP_END, 0) >= 0) {
      return Optional.empty();
    }
    if (startsWith(bytes, FileStoreConstants.PDF_SIGNATURE)) {
      var end = indexOf(bytes, FileStoreConstants.PDF_END, Math.max(0, bytes.length - 1024));
      return end >= 0 ? Optional.of(FileStoreConstants.PDF) : Optional.empty();
    }
    if (startsWith(bytes, FileStoreConstants.PNG_SIGNATURE) && decodes(bytes)) {
      return Optional.of(FileStoreConstants.PNG);
    }
    if (startsWith(bytes, FileStoreConstants.JPEG_SIGNATURE) && decodes(bytes)) {
      return Optional.of(FileStoreConstants.JPEG);
    }
    return Optional.empty();
  }

  static String safeName(@Nullable String original) {
    var name = original == null ? "" : original;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = FileStoreConstants.UNSAFE_NAME_CHARACTERS.matcher(name).replaceAll("_").strip();
    if (name.isEmpty()) {
      name = FileStoreConstants.DEFAULT_NAME;
    }
    return name.length() > FileStoreConstants.MAX_NAME_LENGTH
        ? name.substring(name.length() - FileStoreConstants.MAX_NAME_LENGTH)
        : name;
  }

  // A header can claim any size: a few bytes would otherwise make the decoder allocate gigabytes.
  // So the size is read first, and the image decoded at a fraction of it.
  private static boolean decodes(byte[] bytes) {
    try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
      var readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        return false;
      }
      var reader = readers.next();
      try {
        reader.setInput(input, true, true);
        var pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels > FileStoreConstants.MAX_IMAGE_PIXELS) {
          return false;
        }
        var step = (int) Math.ceil(Math.sqrt((double) pixels / FileStoreConstants.DECODED_PIXELS));
        var param = reader.getDefaultReadParam();
        if (step > 1) {
          param.setSourceSubsampling(step, step, 0, 0);
        }
        return reader.read(0, param) != null;
      } finally {
        reader.dispose();
      }
    } catch (IOException | RuntimeException notAnImage) {
      return false;
    }
  }

  private static boolean startsWith(byte[] bytes, byte[] prefix) {
    return bytes.length >= prefix.length
        && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
  }

  private static int indexOf(byte[] bytes, byte[] needle, int from) {
    for (var i = from; i <= bytes.length - needle.length; i++) {
      if (Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
        return i;
      }
    }
    return -1;
  }
}
