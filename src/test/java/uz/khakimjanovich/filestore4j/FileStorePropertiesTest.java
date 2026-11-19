package uz.khakimjanovich.filestore4j;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

class FileStorePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(FileStoreAutoConfiguration.class))
          .withBean(S3Client.class, () -> mock(S3Client.class)) // no store needed to check rules
          .withPropertyValues(
              "filestore4j.storage.endpoint=http://localhost:8333",
              "filestore4j.storage.region=us-east-1",
              "filestore4j.storage.access-key=demo",
              "filestore4j.buckets.logos.allowed-types=image/png",
              "filestore4j.buckets.logos.max-size=1MB");

  @Test
  void aBucketWithAPositiveLimitStarts() {
    runner
        .withPropertyValues("filestore4j.storage.secret-key=local-only")
        .run(context -> assertThat(context).hasSingleBean(FileStore.class));
  }

  @Test
  void aBucketThatTakesNothingStopsStartup() {
    runner
        .withPropertyValues(
            "filestore4j.storage.secret-key=local-only", "filestore4j.buckets.logos.max-size=0B")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("filestore4j")
                    .hasMessageContaining("max-size"));
  }

  @Test
  void aMissingSecretKeyStopsStartup() {
    runner.run(
        context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessageContaining("filestore4j")
                .hasMessageContaining("secret-key"));
  }

  @Test
  void theDefaultChainIsChosenOnPurpose() {
    runner
        .withPropertyValues("filestore4j.storage.credentials=default-chain")
        .run(context -> assertThat(context).hasSingleBean(FileStore.class));
  }

  @Test
  void aStoreWithoutARegionStopsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileStoreAutoConfiguration.class))
        .withBean(S3Client.class, () -> mock(S3Client.class))
        .withPropertyValues(
            "filestore4j.storage.endpoint=http://localhost:8333",
            "filestore4j.storage.access-key=demo",
            "filestore4j.storage.secret-key=local-only",
            "filestore4j.buckets.logos.allowed-types=image/png",
            "filestore4j.buckets.logos.max-size=1MB")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("filestore4j")
                    .hasMessageContaining("region"));
  }
}
