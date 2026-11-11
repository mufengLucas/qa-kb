package com.qa.kb.it;

import java.time.Duration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared MySQL 8.4 container.
 *
 * <p>The server is started with {@code --ngram_token_size=2}, matching production, so FULLTEXT
 * behaviour in tests is the behaviour the team gets. Tests are skipped when Docker is unavailable.
 */
@SpringBootTest
@Testcontainers
abstract class AbstractMySqlIT {

  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("qa_kb")
          .withUsername("qa")
          .withPassword("qa")
          .withCommand("--ngram_token_size=2")
          .withStartupTimeout(Duration.ofMinutes(3));

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            MYSQL.getJdbcUrl()
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false");
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("kb.seed.enabled", () -> "false");
  }
}
