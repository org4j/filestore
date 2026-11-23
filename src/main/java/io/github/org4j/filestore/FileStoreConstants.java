package io.github.org4j.filestore;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/** The properties' prefix. */
public final class FileStoreConstants {

  /** Every property of this library starts with it. */
  public static final String PROPERTIES_PREFIX = "org4j.filestore";

  static final String FILE_NAME_METADATA = "file-name";
  static final String SHA256_METADATA = "sha256";
  static final String UPLOADED_AT_METADATA = "uploaded-at";

  static final String PDF = "application/pdf";
  static final String PNG = "image/png";
  static final String JPEG = "image/jpeg";

  static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
  static final byte[] PDF_END = "%%EOF".getBytes(StandardCharsets.US_ASCII);
  static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
  static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
  static final byte[] ZIP_END = {'P', 'K', 5, 6};

  static final long MAX_IMAGE_PIXELS = 100_000_000;
  static final long DECODED_PIXELS = 1_000_000;

  static final Pattern UNSAFE_NAME_CHARACTERS = Pattern.compile("[^\\p{L}\\p{N} ._()-]");
  static final int MAX_NAME_LENGTH = 200;
  static final String DEFAULT_NAME = "file";

  static final String LOG_STORED = "Stored {} in {} | {} bytes, {}";
  static final String LOG_CORRUPT = "File {} in {} doesn't match its checksum or metadata";
  static final String LOG_UNAVAILABLE = "File store unavailable: {}";
  static final String LOG_NOT_CLOSED = "File {} in {} was not closed";
  static final String LOG_NOT_DELETED = "File {} in {} was not deleted: {}";
  static final String BUCKET_UNCHECKED =
      "Bucket %s can't be checked: the file store doesn't answer (org4j.filestore.storage.endpoint)";
  static final String BUCKET_MISSING =
      "Bucket %s is configured but doesn't exist in the store; infrastructure creates it";
  static final String BUCKET_UNREADABLE = "Bucket %s can't be checked in the store";

  static final String DEFAULT_CREDENTIALS = "keys";
  static final String DEFAULT_API_CALL_TIMEOUT = "30s";
  static final String DEFAULT_API_CALL_ATTEMPT_TIMEOUT = "10s";

  static final String HEALTH_INDICATOR_BEAN = "fileStoreHealthIndicator";
  static final Duration HEALTH_CALL_TIMEOUT = Duration.ofSeconds(3);

  private FileStoreConstants() {}
}
