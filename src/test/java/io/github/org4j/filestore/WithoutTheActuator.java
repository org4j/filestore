package io.github.org4j.filestore;

import java.lang.reflect.Proxy;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.services.s3.S3Client;

/** An app started in a class loader that has no actuator, by {@link FileStorePropertiesTest}. */
public final class WithoutTheActuator {

  @ImportAutoConfiguration(FileStoreAutoConfiguration.class)
  static class App {

    @Bean
    S3Client s3Client() {
      // Every bucket exists: each call answers nothing.
      return (S3Client)
          Proxy.newProxyInstance(
              S3Client.class.getClassLoader(),
              new Class<?>[] {S3Client.class},
              (proxy, method, args) -> null);
    }
  }

  private WithoutTheActuator() {}

  /** Starts the app and names its {@link FileStore} beans. */
  public static String start() {
    try (var context =
        new SpringApplicationBuilder(App.class)
            .web(WebApplicationType.NONE)
            .properties(
                "org4j.filestore.storage.endpoint=http://localhost:8333",
                "org4j.filestore.storage.region=us-east-1",
                "org4j.filestore.storage.access-key=demo",
                "org4j.filestore.storage.secret-key=local-only",
                "org4j.filestore.buckets.logos.allowed-types=image/png",
                "org4j.filestore.buckets.logos.max-size=1MB")
            .run()) {
      return String.join(",", context.getBeanNamesForType(FileStore.class));
    }
  }
}
