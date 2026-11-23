package io.github.org4j.filestore;

import io.github.org4j.error.GeneralException;
import io.github.org4j.filestore.FileStoreProperties.Bucket;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Files in the configured buckets of an S3-compatible store. A file is kept under a random id; its
 * name, type, checksum and upload time travel with it as the object's metadata.
 *
 * <p>Every failure is a {@link GeneralException} with a {@link FileStoreExceptionStatus}.
 */
public class FileStore {

  private static final Logger log = LoggerFactory.getLogger(FileStore.class);

  private final S3Client s3;
  private final FileStoreProperties properties;

  public FileStore(S3Client s3, FileStoreProperties properties) {
    this.s3 = s3;
    this.properties = properties;
  }

  /**
   * What the store knows of a file.
   *
   * @param id the id the store gave it; the only thing a module keeps
   * @param fileName the uploaded name, its directories dropped and its unsafe characters replaced
   * @param contentType read from the file's bytes, never from the upload's claim
   * @param sha256 hex, checked again whenever the file is read
   */
  public record StoredFile(
      UUID id,
      String bucket,
      String fileName,
      String contentType,
      long size,
      String sha256,
      Instant uploadedAt) {}

  /** A file read whole, its checksum checked. */
  public static final class FileContent {

    private final StoredFile file;
    private final byte[] bytes;

    public FileContent(StoredFile file, byte[] bytes) {
      this.file = file;
      this.bytes = bytes;
    }

    public StoredFile file() {
      return file;
    }

    public byte[] bytes() {
      return bytes;
    }
  }

  /**
   * A file being read. Reading its stream to the end checks the checksum: the last read throws
   * {@code FILE_STORE_FILE_CORRUPT} if the bytes changed.
   */
  public record StreamingFileContent(StoredFile file, InputStream stream) implements AutoCloseable {

    public void transferTo(OutputStream target) throws IOException {
      stream.transferTo(target);
    }

    @Override
    public void close() throws IOException {
      stream.close();
    }
  }

  void checkBuckets() {
    for (var name : properties.buckets().keySet()) {
      try {
        s3.headBucket(head -> head.bucket(name));
      } catch (NoSuchBucketException e) {
        throw new IllegalStateException(FileStoreConstants.BUCKET_MISSING.formatted(name), e);
      } catch (S3Exception e) {
        var message =
            e.statusCode() == 404
                ? FileStoreConstants.BUCKET_MISSING
                : FileStoreConstants.BUCKET_UNREADABLE;
        throw new IllegalStateException(message.formatted(name), e);
      } catch (SdkException e) {
        throw new IllegalStateException(FileStoreConstants.BUCKET_UNCHECKED.formatted(name), e);
      }
    }
  }

  /**
   * Stores an upload if its bytes are one of the bucket's types and it isn't too large.
   *
   * @param bucket a configured bucket's name
   */
  public StoredFile store(String bucket, MultipartFile file) {
    var rules = bucket(bucket);
    if (file.isEmpty()) {
      throw new GeneralException(FileStoreExceptionStatus.FILE_STORE_FILE_EMPTY);
    }
    if (file.getSize() > rules.maxSize().toBytes()) {
      throw new GeneralException(
          FileStoreExceptionStatus.FILE_STORE_FILE_TOO_LARGE, rules.maxSize());
    }
    var bytes = bytes(file);
    var type =
        FileTypes.detect(bytes)
            .filter(rules.allowedTypes()::contains)
            .orElseThrow(
                () ->
                    new GeneralException(
                        FileStoreExceptionStatus.FILE_STORE_TYPE_NOT_ALLOWED,
                        String.join(", ", new TreeSet<>(rules.allowedTypes()))));
    var stored =
        new StoredFile(
            UUID.randomUUID(),
            bucket,
            FileTypes.safeName(file.getOriginalFilename()),
            type,
            bytes.length,
            sha256(bytes),
            Instant.now());
    var metadata =
        Map.of(
            // Metadata travels as HTTP headers, which must be ASCII: S3 refuses the signature
            // otherwise.
            FileStoreConstants.FILE_NAME_METADATA,
                URLEncoder.encode(stored.fileName(), StandardCharsets.UTF_8),
            FileStoreConstants.SHA256_METADATA, stored.sha256(),
            FileStoreConstants.UPLOADED_AT_METADATA, stored.uploadedAt().toString());
    storage(
        () ->
            s3.putObject(
                put ->
                    put.bucket(bucket)
                        .key(stored.id().toString())
                        .contentType(type)
                        .metadata(metadata),
                RequestBody.fromBytes(bytes)));
    log.info(FileStoreConstants.LOG_STORED, stored.id(), bucket, bytes.length, type);
    return stored;
  }

  /**
   * Opens a file without reading it into memory; the caller closes it.
   *
   * @throws GeneralException {@code FILE_STORE_FILE_NOT_FOUND}, or {@code FILE_STORE_FILE_CORRUPT}
   *     if the object lacks the store's metadata
   */
  public StreamingFileContent openStream(String bucket, UUID fileId) {
    bucket(bucket);
    var object =
        found(() -> s3.getObject(get -> get.bucket(bucket).key(fileId.toString())), fileId);
    var stored = described(object, bucket, fileId);
    return new StreamingFileContent(
        stored, new VerifyingInputStream(object, stored.sha256(), fileId, bucket));
  }

  /** Copies a file to {@code target} without reading it into memory, its checksum checked. */
  public void transferTo(String bucket, UUID fileId, OutputStream target) throws IOException {
    try (var streaming = openStream(bucket, fileId)) {
      streaming.transferTo(target);
    }
  }

  /** Reads a file whole; for small files, {@link #openStream} for large ones. */
  public FileContent read(String bucket, UUID fileId) {
    try (var streaming = openStream(bucket, fileId)) {
      return new FileContent(streaming.file(), streaming.stream().readAllBytes());
    } catch (IOException e) {
      log.warn(FileStoreConstants.LOG_UNAVAILABLE, e.getMessage(), e);
      throw new GeneralException(FileStoreExceptionStatus.FILE_STORE_STORAGE_UNAVAILABLE);
    }
  }

  /**
   * Deletes a file; a file already gone counts as deleted. A store that doesn't answer is logged,
   * not thrown: the caller's own deletion goes on, and the file stays behind.
   */
  public void delete(String bucket, UUID fileId) {
    bucket(bucket);
    try {
      s3.deleteObject(delete -> delete.bucket(bucket).key(fileId.toString()));
    } catch (SdkException e) {
      log.warn(FileStoreConstants.LOG_NOT_DELETED, fileId, bucket, e.getMessage());
    }
  }

  private Bucket bucket(String name) {
    var bucket = properties.buckets().get(name);
    if (bucket == null) {
      throw new GeneralException(FileStoreExceptionStatus.FILE_STORE_BUCKET_UNKNOWN, name);
    }
    return bucket;
  }

  private static StoredFile described(
      ResponseInputStream<GetObjectResponse> object, String bucket, UUID fileId) {
    var response = object.response();
    var metadata = response.metadata();
    var name = metadata.get(FileStoreConstants.FILE_NAME_METADATA);
    var checksum = metadata.get(FileStoreConstants.SHA256_METADATA);
    var uploadedAt = metadata.get(FileStoreConstants.UPLOADED_AT_METADATA);
    if (name == null || checksum == null || uploadedAt == null) {
      throw corrupt(object, bucket, fileId);
    }
    try {
      return new StoredFile(
          fileId,
          bucket,
          URLDecoder.decode(name, StandardCharsets.UTF_8),
          response.contentType(),
          response.contentLength(),
          checksum,
          Instant.parse(uploadedAt));
    } catch (DateTimeException | IllegalArgumentException e) {
      throw corrupt(object, bucket, fileId);
    }
  }

  private static GeneralException corrupt(InputStream object, String bucket, UUID fileId) {
    try {
      object.close();
    } catch (IOException e) {
      log.debug(FileStoreConstants.LOG_NOT_CLOSED, fileId, bucket, e);
    }
    log.error(FileStoreConstants.LOG_CORRUPT, fileId, bucket);
    return new GeneralException(FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT, fileId);
  }

  private static <T> T storage(Supplier<T> call) {
    try {
      return call.get();
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  private static <T> T found(Supplier<T> call, UUID fileId) {
    try {
      return call.get();
    } catch (NoSuchKeyException e) {
      throw new GeneralException(FileStoreExceptionStatus.FILE_STORE_FILE_NOT_FOUND, fileId);
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        throw new GeneralException(FileStoreExceptionStatus.FILE_STORE_FILE_NOT_FOUND, fileId);
      }
      throw unavailable(e);
    } catch (SdkException e) {
      throw unavailable(e);
    }
  }

  private static GeneralException unavailable(SdkException e) {
    log.warn(FileStoreConstants.LOG_UNAVAILABLE, e.getMessage(), e);
    return new GeneralException(FileStoreExceptionStatus.FILE_STORE_STORAGE_UNAVAILABLE);
  }

  private static byte[] bytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new GeneralException(FileStoreExceptionStatus.FILE_STORE_FILE_EMPTY);
    }
  }

  private static String sha256(byte[] bytes) {
    return HexFormat.of().formatHex(sha256().digest(bytes));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final class VerifyingInputStream extends FilterInputStream {

    private final MessageDigest digest = sha256();
    private final String expected;
    private final UUID fileId;
    private final String bucket;
    private boolean verified;

    private VerifyingInputStream(InputStream in, String expected, UUID fileId, String bucket) {
      super(in);
      this.expected = expected;
      this.fileId = fileId;
      this.bucket = bucket;
    }

    @Override
    public int read() throws IOException {
      var b = super.read();
      if (b == -1) {
        verify();
      } else {
        digest.update((byte) b);
      }
      return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      var count = super.read(b, off, len);
      if (count == -1) {
        verify();
      } else {
        digest.update(b, off, count);
      }
      return count;
    }

    private void verify() {
      if (verified) {
        return;
      }
      verified = true;
      if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
        log.error(FileStoreConstants.LOG_CORRUPT, fileId, bucket);
        throw new GeneralException(FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT, fileId);
      }
    }
  }
}
