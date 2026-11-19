package io.github.org4j.filestore;

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
              "org4j.filestore.storage.endpoint=http://localhost:8333",
              "org4j.filestore.storage.region=us-east-1",
              "org4j.filestore.storage.access-key=demo",
              "org4j.filestore.buckets.logos.allowed-types=image/png",
              "org4j.filestore.buckets.logos.max-size=1MB");

  @Test
  void aBucketWithAPositiveLimitStarts() {
    runner
        .withPropertyValues("org4j.filestore.storage.secret-key=local-only")
        .run(context -> assertThat(context).hasSingleBean(FileStore.class));
  }

  @Test
  void aBucketThatTakesNothingStopsStartup() {
    runner
        .withPropertyValues(
            "org4j.filestore.storage.secret-key=local-only",
            "org4j.filestore.buckets.logos.max-size=0B")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("org4j.filestore")
                    .hasMessageContaining("max-size"));
  }

  @Test
  void aMissingSecretKeyStopsStartup() {
    runner.run(
        context ->
            assertThat(context)
                .getFailure()
                .rootCause()
                .hasMessageContaining("org4j.filestore")
                .hasMessageContaining("secret-key"));
  }

  @Test
  void theDefaultChainIsChosenOnPurpose() {
    runner
        .withPropertyValues("org4j.filestore.storage.credentials=default-chain")
        .run(context -> assertThat(context).hasSingleBean(FileStore.class));
  }

  @Test
  void aStoreWithoutARegionStopsStartup() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(FileStoreAutoConfiguration.class))
        .withBean(S3Client.class, () -> mock(S3Client.class))
        .withPropertyValues(
            "org4j.filestore.storage.endpoint=http://localhost:8333",
            "org4j.filestore.storage.access-key=demo",
            "org4j.filestore.storage.secret-key=local-only",
            "org4j.filestore.buckets.logos.allowed-types=image/png",
            "org4j.filestore.buckets.logos.max-size=1MB")
        .run(
            context ->
                assertThat(context)
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("org4j.filestore")
                    .hasMessageContaining("region"));
  }
}
