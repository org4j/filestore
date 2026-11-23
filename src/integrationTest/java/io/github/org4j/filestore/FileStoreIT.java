package io.github.org4j.filestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.org4j.error.GeneralException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(
    classes = FileStoreIT.class,
    properties = {
      "org4j.filestore.storage.region=us-east-1",
      "org4j.filestore.storage.access-key=demo",
      "org4j.filestore.storage.secret-key=local-only",
      "org4j.filestore.storage.path-style=true",
      "org4j.filestore.buckets.partner-documents.allowed-types=application/pdf,image/png,image/jpeg",
      "org4j.filestore.buckets.partner-documents.max-size=10MB",
      "org4j.filestore.buckets.sales-point-logos.allowed-types=image/png,image/jpeg",
      "org4j.filestore.buckets.sales-point-logos.max-size=1MB"
    })
@ImportAutoConfiguration(FileStoreAutoConfiguration.class)
@Testcontainers
class FileStoreIT {

  @Container
  static final GenericContainer<?> SEAWEEDFS =
      new GenericContainer<>(ResourceBundle.getBundle("images").getString("seaweedfs"))
          .withCommand(
              "server",
              "-s3",
              "-s3.config=/etc/seaweedfs/s3.json",
              "-dir=/data",
              "-master.volumeSizeLimitMB=64",
              "-volume.max=8")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("seaweedfs-s3.json"), "/etc/seaweedfs/s3.json")
          .withExposedPorts(8333)
          .waitingFor(
              Wait.forHttp("/")
                  .forPort(8333)
                  .forStatusCode(403)
                  .withStartupTimeout(Duration.ofMinutes(2)));

  @BeforeAll
  static void buckets() throws Exception {
    for (var bucket : new String[] {"partner-documents", "sales-point-logos"}) {
      var created =
          SEAWEEDFS.execInContainer(
              "sh", "-c", "echo 's3.bucket.create -name " + bucket + "' | weed shell");
      assertThat(created.getExitCode()).as(created.getStderr()).isZero();
    }
  }

  @DynamicPropertySource
  static void storage(DynamicPropertyRegistry registry) {
    registry.add("org4j.filestore.storage.endpoint", FileStoreIT::endpoint);
  }

  private static String endpoint() {
    return "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333);
  }

  @Autowired FileStore files;
  @Autowired S3Client s3;
  @Autowired FileStoreProperties properties;
  @Autowired HealthIndicator fileStoreHealthIndicator;

  @Test
  void aFileIsStoredWithItsTypeAndChecksumAndReadBackAsIs() {
    var pdf =
        "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
    var stored = files.store("partner-documents", file("../../Ustav.png", "image/png", pdf));
    assertThat(stored.contentType())
        .as("from the bytes, not the name")
        .isEqualTo("application/pdf");
    assertThat(stored.fileName()).isEqualTo("Ustav.png");
    assertThat(stored.sha256()).hasSize(64);

    var read = files.read("partner-documents", stored.id());
    assertThat(read.bytes()).isEqualTo(pdf);
    assertThat(read.file()).isEqualTo(stored);

    files.delete("partner-documents", stored.id());
    assertThatThrownBy(() -> files.read("partner-documents", stored.id()))
        .satisfies(e -> hasCode(e, FileStoreExceptionStatus.FILE_STORE_FILE_NOT_FOUND));
  }

  @Test
  void aNameInAnyAlphabetComesBackAsIs() {
    var pdf = "%PDF-1.4\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    var stored =
        files.store("partner-documents", file("Устав Oʻzbekiston.pdf", "application/pdf", pdf));
    assertThat(files.read("partner-documents", stored.id()).file().fileName())
        .isEqualTo("Устав Oʻzbekiston.pdf");
  }

  @Test
  void aFileCanBeStreamedAndTransferredWithoutHeapMaterialization() throws Exception {
    var pdf =
        "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n"
            .getBytes(StandardCharsets.US_ASCII);
    var stored = files.store("partner-documents", file("Document.pdf", "application/pdf", pdf));

    try (var streaming = files.openStream("partner-documents", stored.id())) {
      assertThat(streaming.file().fileName()).isEqualTo("Document.pdf");
      assertThat(streaming.file().contentType()).isEqualTo("application/pdf");
      var streamedBytes = streaming.stream().readAllBytes();
      assertThat(streamedBytes).isEqualTo(pdf);
    }

    var out = new ByteArrayOutputStream();
    files.transferTo("partner-documents", stored.id(), out);
    assertThat(out.toByteArray()).isEqualTo(pdf);

    files.delete("partner-documents", stored.id());
  }

  @Test
  void eachBucketTakesOnlyItsTypesAndSizes() throws Exception {
    var svg = "<svg onload=alert(1)/>".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(() -> files.store("sales-point-logos", file("logo.png", "image/png", svg)))
        .satisfies(e -> hasCode(e, FileStoreExceptionStatus.FILE_STORE_TYPE_NOT_ALLOWED));
    var big = new byte[1_100_000];
    assertThatThrownBy(() -> files.store("sales-point-logos", file("logo.png", "image/png", big)))
        .satisfies(e -> hasCode(e, FileStoreExceptionStatus.FILE_STORE_FILE_TOO_LARGE));
    assertThat(files.store("sales-point-logos", file("logo.png", "image/png", png())).contentType())
        .isEqualTo("image/png");
    assertThatThrownBy(() -> files.store("anything", file("a.png", "image/png", png())))
        .satisfies(e -> hasCode(e, FileStoreExceptionStatus.FILE_STORE_BUCKET_UNKNOWN));
  }

  @Test
  void aConfiguredBucketTheStoreLacksStopsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileStoreAutoConfiguration.class))
        .withPropertyValues(
            "org4j.filestore.storage.endpoint=" + endpoint(),
            "org4j.filestore.storage.region=us-east-1",
            "org4j.filestore.storage.access-key=demo",
            "org4j.filestore.storage.secret-key=local-only",
            "org4j.filestore.storage.path-style=true",
            "org4j.filestore.buckets.contracts.allowed-types=application/pdf",
            "org4j.filestore.buckets.contracts.max-size=1MB")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasStackTraceContaining("Bucket contracts"));
  }

  @Test
  void aFileChangedBehindTheStoresBackIsCorrupt() {
    var pdf = "%PDF-1.4\ntrailer << >>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    var stored = files.store("partner-documents", file("Ustav.pdf", "application/pdf", pdf));
    var original =
        s3.headObject(head -> head.bucket("partner-documents").key(stored.id().toString()));
    s3.putObject(
        put ->
            put.bucket("partner-documents")
                .key(stored.id().toString())
                .contentType("application/pdf")
                .metadata(original.metadata()),
        RequestBody.fromBytes("%PDF-1.4 tampered %%EOF".getBytes(StandardCharsets.US_ASCII)));
    assertThatThrownBy(() -> files.read("partner-documents", stored.id()))
        .satisfies(e -> hasCode(e, FileStoreExceptionStatus.FILE_STORE_FILE_CORRUPT));
  }

  @Test
  void healthIsUpWithOurCredentialsAndDownWithTheStoresErrorCodeWithWrong() {
    assertThat(fileStoreHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
    try (var wrong =
        S3Client.builder()
            .endpointOverride(java.net.URI.create(endpoint()))
            .region(Region.US_EAST_1)
            .forcePathStyle(true)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("demo", "wrong-key")))
            .build()) {
      var health = new FileStoreHealthIndicator(wrong, properties).health();
      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails()).containsKeys("partner-documents", "sales-point-logos");
      assertThat(health.getDetails().toString()).contains("403").doesNotContain("wrong-key");
    }
  }

  private static void hasCode(Throwable e, FileStoreExceptionStatus status) {
    assertThat(e).isInstanceOf(GeneralException.class);
    assertThat(((GeneralException) e).getStatus()).isEqualTo(status);
  }

  private static MockMultipartFile file(String name, String type, byte[] bytes) {
    return new MockMultipartFile("file", name, type, bytes);
  }

  private static byte[] png() throws Exception {
    var out = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", out);
    return out.toByteArray();
  }
}
