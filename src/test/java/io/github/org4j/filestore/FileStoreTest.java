package io.github.org4j.filestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.org4j.error.GeneralException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("unchecked")
class FileStoreTest {

  private static final String PDF_TYPE = "application/pdf";
  private static final byte[] PDF =
      "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
  private static final String PDF_SHA256 =
      "2424e4b37c25d8dac4c66fd4ecd780c717b828582c3f9a9fc718672d2d5e31f1";

  private final S3Client s3 = mock(S3Client.class);
  private final FileStore files =
      new FileStore(
          s3,
          new FileStoreProperties(
              null,
              Map.of(
                  "partner-documents",
                  new FileStoreProperties.Bucket(
                      Set.of("application/pdf", "image/png"), DataSize.ofKilobytes(1)))));

  @Nested
  class Storing {

    @Test
    void aFileIsKeptUnderANewIdWithWhatItIsAsMetadata() {
      var stored = files.store("partner-documents", upload("../../Ustav.png", PDF));

      var put = ArgumentCaptor.forClass(Consumer.class);
      var body = ArgumentCaptor.forClass(RequestBody.class);
      verify(s3).putObject(put.capture(), body.capture());
      var request = PutObjectRequest.builder();
      put.getValue().accept(request);
      assertThat(request.build())
          .satisfies(
              r -> {
                assertThat(r.bucket()).isEqualTo("partner-documents");
                assertThat(r.key()).isEqualTo(stored.id().toString());
                assertThat(r.contentType()).isEqualTo("application/pdf");
                assertThat(r.metadata())
                    .containsExactlyInAnyOrderEntriesOf(
                        Map.of(
                            "file-name",
                            "Ustav.png",
                            "sha256",
                            PDF_SHA256,
                            "uploaded-at",
                            stored.uploadedAt().toString()));
              });
      assertThat(stored.contentType()).as("from the bytes, not the name").isEqualTo(PDF_TYPE);
      assertThat(stored.size()).isEqualTo(PDF.length);
      assertThat(stored.sha256()).isEqualTo(PDF_SHA256);
    }

    @Test
    void aNameInAnyAlphabetTravelsAsAsciiAndComesBackAsIs() {
      var stored = files.store("partner-documents", upload("Устав Oʻzbekiston.pdf", PDF));
      var put = ArgumentCaptor.forClass(Consumer.class);
      verify(s3).putObject(put.capture(), any(RequestBody.class));
      var request = PutObjectRequest.builder();
      put.getValue().accept(request);
      var sent = request.build().metadata().get("file-name");
      assertThat(sent).isNotNull().matches("\\p{ASCII}+");

      answer(
          PDF,
          Map.of("file-name", sent, "sha256", PDF_SHA256, "uploaded-at", "2026-10-21T00:00:00Z"));
      assertThat(files.read("partner-documents", stored.id()).file().fileName())
          .isEqualTo("Устав Oʻzbekiston.pdf");
    }

    @Test
    void anEmptyFileIsRefused() {
      assertFails(
          () -> files.store("partner-documents", upload("a.pdf", new byte[0])),
          FileStoreExceptionStatus.FILE_STORE_FILE_EMPTY);
      verify(s3, never()).putObject(any(Consumer.class), any(RequestBody.class));
    }

    @Test
    void aFileLargerThanTheBucketTakesIsRefusedNamingTheLimit() {
      assertThatThrownBy(() -> files.store("partner-documents", upload("a.pdf", new byte[1025])))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e -> {
                assertThat(e.getStatus())
                    .isEqualTo(FileStoreExceptionStatus.FILE_STORE_FILE_TOO_LARGE);
                assertThat(e.getParams()).containsExactly(DataSize.ofKilobytes(1));
              });
    }

    @Test
    void aTypeTheBucketDoesntTakeIsRefusedListingTheOnesItDoes() {
      var svg = "<svg onload=alert(1)/>".getBytes(StandardCharsets.UTF_8);
      assertThatThrownBy(() -> files.store("partner-documents", upload("logo.png", svg)))
          .isInstanceOfSatisfying(
              GeneralException.class,
              e -> {
                assertThat(e.getStatus())
                    .isEqualTo(FileStoreExceptionStatus.FILE_STORE_TYPE_NOT_ALLOWED);
                assertThat(e.getParams()).containsExactly("application/pdf, image/png");
              });
    }

    @Test
    void aStoreThatDoesntAnswerIsUnavailable() {
      when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
          .thenThrow(SdkClientException.create("connection refused"));
      assertFails(
          () -> files.store("partner-documents", upload("a.pdf", PDF)),
          FileStoreExceptionStatus.FILE_STORE_STORAGE_UNAVAILABLE);
    }
  }

  @Nested
  class Reading {

    @Test
    void aFileIsReadBackWithWhatItIs() {
      answer(PDF, metadata(PDF_SHA256));
      var id = UUID.randomUUID();
      var read = files.read("partner-documents", id);
      assertThat(read.bytes()).isEqualTo(PDF);
      assertThat(read.file())
          .isEqualTo(
              new FileStore.StoredFile(
                  id,
                  "partner-documents",
                  "Doc.pdf",
                  PDF_TYPE,
                  PDF.length,
                  PDF_SHA256,
                  java.time.Instant.parse("2026-10-21T00:00:00Z")));
    }

    @Test
    void aFileIsStreamedAndItsChecksumCheckedAtTheEnd() throws IOException {
      answer(PDF, metadata(PDF_SHA256));
      try (var streaming = files.openStream("partner-documents", UUID.randomUUID())) {
        assertThat(streaming.file().fileName()).isEqualTo("Doc.pdf");
        assertThat(streaming.stream().readAllBytes()).isEqualTo(PDF);
      }
    }

    @Test
    void aFileIsTransferredStraightToItsTarget() throws IOException {
      answer(PDF, metadata(PDF_SHA256));
      var out = new ByteArrayOutputStream();
      files.transferTo("partner-documents", UUID.randomUUID(), out);
      assertThat(out.toByteArray()).isEqualTo(PDF);
    }

    @Test
    void aFileThatNoLongerMatchesItsChecksumIsCorrupt() {
      answer("%PDF-1.4 tampered".getBytes(StandardCharsets.US_ASCII), metadata(PDF_SHA256));
      assertFails(
          () -> files.read("partner-documents", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT);
    }

    @Test
    void aStreamThatNoLongerMatchesItsChecksumIsCorruptAtItsEnd() {
      answer("%PDF-1.4 tampered".getBytes(StandardCharsets.US_ASCII), metadata(PDF_SHA256));
      assertFails(
          () -> {
            try (var streaming = files.openStream("partner-documents", UUID.randomUUID())) {
              streaming.stream().readAllBytes();
            }
          },
          FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT);
    }

    @Test
    void anObjectWithoutTheStoresMetadataIsCorrupt() {
      answer(new byte[] {1, 2, 3}, Map.of());
      assertFails(
          () -> files.read("partner-documents", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT);
    }

    @Test
    void anObjectWithAnUnreadableUploadTimeIsCorruptAndItsStreamClosed() {
      var closed = new AtomicBoolean();
      var body =
          new ByteArrayInputStream(PDF) {
            @Override
            public void close() {
              closed.set(true);
            }
          };
      when(s3.getObject(any(Consumer.class)))
          .thenReturn(
              new ResponseInputStream<>(
                  response(
                      PDF.length,
                      Map.of(
                          "file-name",
                          "Doc.pdf",
                          "sha256",
                          PDF_SHA256,
                          "uploaded-at",
                          "yesterday")),
                  body));
      assertFails(
          () -> files.openStream("partner-documents", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT);
      assertThat(closed).isTrue();
    }

    @Test
    void anObjectWithAnUnreadableNameIsCorrupt() {
      answer(
          PDF,
          Map.of("file-name", "%ZZ", "sha256", PDF_SHA256, "uploaded-at", "2026-10-21T00:00:00Z"));
      assertFails(
          () -> files.read("partner-documents", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT);
    }

    @Test
    void aMissingFileIsNotFound() {
      when(s3.getObject(any(Consumer.class)))
          .thenThrow(NoSuchKeyException.builder().statusCode(404).build());
      assertFails(
          () -> files.read("partner-documents", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_FILE_NOT_FOUND);
    }

    @Test
    void aStoreThatDoesntAnswerIsUnavailable(CapturedOutput output) {
      when(s3.getObject(any(Consumer.class)))
          .thenThrow(SdkClientException.create("connection refused"));
      assertFails(
          () -> files.read("partner-documents", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_STORAGE_UNAVAILABLE);
      assertThat(output).contains("File store unavailable: connection refused");
    }

    @Test
    void anUnknownBucketIsTheCodesMistake() {
      assertFails(
          () -> files.read("anything", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_BUCKET_UNKNOWN);
    }
  }

  @Nested
  class Deleting {

    @Test
    void aStoreThatDoesntAnswerIsLoggedNotThrown(CapturedOutput output) {
      var id = UUID.randomUUID();
      when(s3.deleteObject(any(Consumer.class)))
          .thenThrow(SdkClientException.create("connection refused"));
      files.delete("partner-documents", id);
      assertThat(output).contains("File " + id + " in partner-documents was not deleted");
    }

    @Test
    void anUnknownBucketIsTheCodesMistake() {
      assertFails(
          () -> files.delete("anything", UUID.randomUUID()),
          FileStoreExceptionStatus.FILE_STORE_BUCKET_UNKNOWN);
    }
  }

  @Nested
  class Startup {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FileStoreAutoConfiguration.class))
            .withBean(S3Client.class, () -> s3)
            .withPropertyValues(
                "org4j.filestore.storage.endpoint=http://localhost:8333",
                "org4j.filestore.storage.region=us-east-1",
                "org4j.filestore.storage.access-key=demo",
                "org4j.filestore.storage.secret-key=local-only",
                "org4j.filestore.buckets.partner-documents.allowed-types=application/pdf",
                "org4j.filestore.buckets.partner-documents.max-size=10MB");

    @Test
    void aMissingBucketStopsStartupNamingIt() {
      when(s3.headBucket(any(Consumer.class)))
          .thenThrow(NoSuchBucketException.builder().statusCode(404).message("no").build());
      runner.run(
          context ->
              assertThat(context)
                  .getFailure()
                  .hasStackTraceContaining(
                      "Bucket partner-documents is configured but doesn't exist"));
    }

    @Test
    void aStoreThatIsDownAtStartupNamesTheBucket() {
      when(s3.headBucket(any(Consumer.class)))
          .thenThrow(SdkClientException.create("connection refused"));
      runner.run(
          context ->
              assertThat(context)
                  .getFailure()
                  .hasStackTraceContaining("Bucket partner-documents can't be checked"));
    }
  }

  private static MockMultipartFile upload(String name, byte[] bytes) {
    return new MockMultipartFile("file", name, "application/octet-stream", bytes);
  }

  private static Map<String, String> metadata(String sha256) {
    return Map.of("file-name", "Doc.pdf", "sha256", sha256, "uploaded-at", "2026-10-21T00:00:00Z");
  }

  private static GetObjectResponse response(int length, Map<String, String> metadata) {
    return GetObjectResponse.builder()
        .contentType(PDF_TYPE)
        .contentLength((long) length)
        .metadata(metadata)
        .build();
  }

  private void answer(byte[] bytes, Map<String, String> metadata) {
    when(s3.getObject(any(Consumer.class)))
        .thenReturn(
            new ResponseInputStream<>(
                response(bytes.length, metadata), new ByteArrayInputStream(bytes)));
  }

  private static void assertFails(ThrowingCallable call, FileStoreExceptionStatus status) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            GeneralException.class, e -> assertThat(e.getStatus()).isEqualTo(status));
  }
}
