package com.qa.kb.it;

import org.testcontainers.DockerClientFactory;

/** Docker probe used by {@code @EnabledIf} so DB tests skip on machines without Docker. */
final class DockerAvailability {

  private DockerAvailability() {}

  static boolean available() {
    try {
      return DockerClientFactory.instance().isDockerAvailable();
    } catch (Throwable unavailable) {
      return false;
    }
  }
}
