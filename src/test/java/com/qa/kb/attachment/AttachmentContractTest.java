package com.qa.kb.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.kb.security.SessionAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 附件上传/下载/删除的端到端契约（真实过滤链 + 真实 SQLite + 真实落盘）。
 *
 * <p>P2 Phase 1：页面附件从元数据表（schema 已备）到 HTTP 面的完整链路，
 * 含中文文件名的 RFC 5987 下载头与删除后的 404 回收。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("附件契约：上传 / 下载 / 删除")
class AttachmentContractTest {

  private static final String ADMIN_PASSWORD = "AttachTest@2026";
  private static final Path TEMP_DIR = createTempDir();

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    String dbPath =
        TEMP_DIR.resolve("kb-attach-test.db").toAbsolutePath().toString().replace('\\', '/');
    registry.add(
        "spring.datasource.url",
        () ->
            "jdbc:sqlite:"
                + dbPath
                + "?journal_mode=WAL&busy_timeout=5000&synchronous=NORMAL");
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.datasource.hikari.connection-init-sql", () -> "PRAGMA foreign_keys=ON");
    registry.add("kb.seed.enabled", () -> "true");
    registry.add("kb.seed.demo", () -> "false");
    registry.add("kb.seed.admin-password", () -> ADMIN_PASSWORD);
    registry.add("kb.backup.enabled", () -> "false");
    registry.add(
        "kb.upload-dir", () -> TEMP_DIR.resolve("uploads").toAbsolutePath().toString());
    registry.add("logging.file.name", () -> TEMP_DIR.resolve("kb-attach-test.log").toString());
  }

  @Test
  @DisplayName("上传 → 列表 → 下载（中文名）→ 删除 → 下载 404")
  void uploadListDownloadDeleteRoundTrip() throws Exception {
    Cookie session = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();
    long pageId = createPageWithSpace(session, csrf);

    byte[] content = "附件内容-attachment-bytes".getBytes(StandardCharsets.UTF_8);
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "测试报告-第一版.txt", MediaType.TEXT_PLAIN_VALUE, content);

    // 上传 201：返回元数据，落盘 1 个文件
    MvcResult uploaded =
        mvc.perform(
                multipart("/api/pages/" + pageId + "/attachments")
                    .file(file)
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.filename").value("测试报告-第一版.txt"))
            .andExpect(jsonPath("$.size").value(content.length))
            .andExpect(jsonPath("$.mime").value("text/plain"))
            .andReturn();
    long attachmentId =
        objectMapper.readTree(uploaded.getResponse().getContentAsString()).path("id").asLong();
    assertThat(attachmentId).isPositive();
    try (var stored = Files.list(TEMP_DIR.resolve("uploads"))) {
      assertThat(stored).hasSize(1);
    }

    // 列表 1 条
    mvc.perform(get("/api/pages/" + pageId + "/attachments").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].filename").value("测试报告-第一版.txt"));

    // 下载：字节一致，中文名走 RFC 5987（filename*=UTF-8''...）
    MvcResult download =
        mvc.perform(get("/api/attachments/" + attachmentId).cookie(session))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("%E6%B5%8B%E8%AF%95")))
            .andReturn();
    assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(content);

    // 删除 204 → 列表清空 → 下载 404 → 落盘文件回收
    mvc.perform(
            delete("/api/attachments/" + attachmentId)
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/pages/" + pageId + "/attachments").cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/attachments/" + attachmentId).cookie(session))
        .andExpect(status().isNotFound());
    try (var stored = Files.list(TEMP_DIR.resolve("uploads"))) {
      assertThat(stored).isEmpty();
    }
  }

  @Test
  @DisplayName("上传到不存在/已删页面 → 404，不落盘")
  void uploadToMissingPageReturns404() throws Exception {
    Cookie session = loginAsAdmin();
    Cookie csrf = freshCsrfCookie();

    MockMultipartFile file =
        new MockMultipartFile("file", "x.txt", MediaType.TEXT_PLAIN_VALUE, "x".getBytes());

    mvc.perform(
            multipart("/api/pages/999999/attachments")
                .file(file)
                .cookie(session, csrf)
                .header("X-XSRF-TOKEN", csrf.getValue()))
        .andExpect(status().isNotFound());
  }

  /* --------------------------------------------------------------- helpers */

  private long createPageWithSpace(Cookie session, Cookie csrf) throws Exception {
    MvcResult space =
        mvc.perform(
                post("/api/spaces")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"附件契约空间\",\"slug\":\"attach-contract\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    long spaceId =
        objectMapper.readTree(space.getResponse().getContentAsString()).path("id").asLong();

    MvcResult page =
        mvc.perform(
                post("/api/spaces/" + spaceId + "/pages")
                    .cookie(session, csrf)
                    .header("X-XSRF-TOKEN", csrf.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"title\":\"附件页面\",\"contentMd\":\"# attach\"}"))
            .andExpect(status().isCreated())
            .andReturn();
    return objectMapper.readTree(page.getResponse().getContentAsString()).path("id").asLong();
  }

  private Cookie loginAsAdmin() throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"login\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andReturn();
    Cookie session = result.getResponse().getCookie(SessionAuthenticationFilter.COOKIE_NAME);
    assertThat(session).as("login must set the session cookie").isNotNull();
    return session;
  }

  private Cookie freshCsrfCookie() throws Exception {
    MvcResult result = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
    Cookie csrf = result.getResponse().getCookie("XSRF-TOKEN");
    assertThat(csrf).as("XSRF-TOKEN cookie must be set").isNotNull();
    return csrf;
  }

  private static Path createTempDir() {
    try {
      return Files.createTempDirectory("qa-kb-attach-test");
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }
}
