package com.qa.kb.testcase;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.TestCaseRunRow;
import com.qa.kb.model.TestCaseView;
import com.qa.kb.persistence.TestCaseMapper;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用例管理与执行记录（P2 Phase 4）。
 *
 * <p>独立模块：用例只靠 {@code module} 字段归属，不挂空间也不挂项目。校验风格与
 * {@code ProjectService} 一致——必填/超长/枚举非法一律抛 {@link IllegalArgumentException}，
 * 由 {@code GlobalExceptionHandler} 统一转 400；资源缺失抛 {@link ApiException#notFound}。
 */
public class TestCaseService {

  public static final int MAX_MODULE_LENGTH = 200;
  public static final int MAX_TITLE_LENGTH = 200;
  public static final int MAX_TEXT_LENGTH = 5000;
  public static final int MAX_NOTE_LENGTH = 1000;
  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;
  public static final String DEFAULT_PRIORITY = "P2";
  public static final String DEFAULT_STATUS = "ACTIVE";

  private static final Set<String> PRIORITIES = Set.of("P0", "P1", "P2", "P3");
  private static final Set<String> STATUSES = Set.of("ACTIVE", "ARCHIVED");
  private static final Set<String> RESULTS = Set.of("PASS", "FAIL", "BLOCKED", "SKIPPED");

  private final TestCaseMapper testCaseMapper;

  public TestCaseService(TestCaseMapper testCaseMapper) {
    this.testCaseMapper = testCaseMapper;
  }

  /** 列表结果：命中总数 + 当前页 + 全部已用模块（筛选下拉，一次请求拿全）。 */
  public record CasePage(int total, List<TestCaseView> items, List<String> modules) {}

  /* ------------------------------------------------------------------ 查询 */

  /**
   * 分页列表。{@code module} / {@code status} 精确匹配，{@code q} 对标题、模块、步骤做 LIKE
   * （服务端先转义通配符，避免用户输入的 {@code %} 变成全表匹配）。
   */
  public CasePage list(String module, String q, String status, Integer limit, Integer offset) {
    String cleanModule = module == null ? "" : requireOptional(module, MAX_MODULE_LENGTH, "模块");
    String cleanStatus = status == null || status.isBlank() ? "" : normalizeStatus(status);
    String pattern = q == null || q.isBlank() ? null : likePattern(q);
    int cleanLimit = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
    int cleanOffset = offset == null ? 0 : Math.max(0, offset);

    int total = testCaseMapper.count(cleanModule, cleanStatus, pattern);
    List<TestCaseView> items =
        testCaseMapper.page(cleanModule, cleanStatus, pattern, cleanLimit, cleanOffset);
    return new CasePage(total, items, testCaseMapper.listModules());
  }

  public TestCaseView get(long id) {
    return find(id);
  }

  public List<String> modules() {
    return testCaseMapper.listModules();
  }

  /* ------------------------------------------------------------------ 写入 */

  @Transactional
  public TestCaseView create(
      String module,
      String title,
      String precondition,
      String steps,
      String expected,
      String priority,
      String status,
      long creatorId) {
    Parsed parsed = parse(module, title, precondition, steps, expected, priority, status);
    testCaseMapper.insert(
        parsed.module(),
        parsed.title(),
        parsed.precondition(),
        parsed.steps(),
        parsed.expected(),
        parsed.priority(),
        parsed.status(),
        creatorId);
    return find(testCaseMapper.lastInsertId());
  }

  @Transactional
  public TestCaseView update(
      long id,
      String module,
      String title,
      String precondition,
      String steps,
      String expected,
      String priority,
      String status) {
    find(id);
    Parsed parsed = parse(module, title, precondition, steps, expected, priority, status);
    testCaseMapper.update(
        id,
        parsed.module(),
        parsed.title(),
        parsed.precondition(),
        parsed.steps(),
        parsed.expected(),
        parsed.priority(),
        parsed.status());
    return find(id);
  }

  /** 硬删除：执行记录随用例一起消失（外键 ON DELETE CASCADE）。 */
  @Transactional
  public void delete(long id) {
    find(id);
    testCaseMapper.delete(id);
  }

  /* ------------------------------------------------------------ 执行记录 */

  public List<TestCaseRunRow> listRuns(long caseId) {
    find(caseId);
    return testCaseMapper.listRuns(caseId);
  }

  @Transactional
  public TestCaseRunRow addRun(long caseId, String result, String note, long executorId) {
    find(caseId);
    String cleanResult = normalizeResult(result);
    String cleanNote = note == null ? "" : requireOptional(note, MAX_NOTE_LENGTH, "备注");
    testCaseMapper.insertRun(caseId, cleanResult, cleanNote, executorId);
    return testCaseMapper
        .findRun(testCaseMapper.lastInsertId())
        .orElseThrow(() -> ApiException.notFound("执行记录不存在"));
  }

  /** 删除一条记错的执行记录；记录必须属于该用例，否则 404。 */
  @Transactional
  public void deleteRun(long caseId, long runId) {
    TestCaseRunRow run =
        testCaseMapper
            .findRun(runId)
            .orElseThrow(() -> ApiException.notFound("执行记录不存在"));
    if (run.caseId() != caseId) {
      throw ApiException.notFound("执行记录不存在");
    }
    testCaseMapper.deleteRun(runId);
  }

  /* ---------------------------------------------------------------- 校验 */

  private record Parsed(
      String module,
      String title,
      String precondition,
      String steps,
      String expected,
      String priority,
      String status) {}

  private Parsed parse(
      String module,
      String title,
      String precondition,
      String steps,
      String expected,
      String priority,
      String status) {
    return new Parsed(
        requireOptional(module, MAX_MODULE_LENGTH, "模块"),
        requireText(title, MAX_TITLE_LENGTH, "用例标题"),
        requireOptional(precondition, MAX_TEXT_LENGTH, "前置条件"),
        requireOptional(steps, MAX_TEXT_LENGTH, "操作步骤"),
        requireOptional(expected, MAX_TEXT_LENGTH, "预期结果"),
        normalizePriority(priority),
        normalizeStatus(status));
  }

  private TestCaseView find(long id) {
    return testCaseMapper
        .findViewById(id)
        .orElseThrow(() -> ApiException.notFound("用例不存在"));
  }

  private static String requireText(String text, int max, String label) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    return requireOptional(text, max, label);
  }

  /** 可空字段：空白归一成空串，但长度上限照查。 */
  private static String requireOptional(String text, int max, String label) {
    String clean = text == null ? "" : text.strip();
    if (clean.length() > max) {
      throw new IllegalArgumentException(label + "不能超过 " + max + " 个字符");
    }
    return clean;
  }

  private static String normalizePriority(String priority) {
    if (priority == null || priority.isBlank()) {
      return DEFAULT_PRIORITY;
    }
    String clean = priority.strip().toUpperCase(Locale.ROOT);
    if (!PRIORITIES.contains(clean)) {
      throw new IllegalArgumentException("未知优先级: " + priority);
    }
    return clean;
  }

  private static String normalizeStatus(String status) {
    if (status == null || status.isBlank()) {
      return DEFAULT_STATUS;
    }
    String clean = status.strip().toUpperCase(Locale.ROOT);
    if (!STATUSES.contains(clean)) {
      throw new IllegalArgumentException("未知用例状态: " + status);
    }
    return clean;
  }

  private static String normalizeResult(String result) {
    if (result == null || result.isBlank()) {
      throw new IllegalArgumentException("执行结果不能为空");
    }
    String clean = result.strip().toUpperCase(Locale.ROOT);
    if (!RESULTS.contains(clean)) {
      throw new IllegalArgumentException("未知执行结果: " + result);
    }
    return clean;
  }

  /**
   * LIKE 模式预转义，转义符是 {@code !}（见 mapper XML 注释：比反斜杠少一层 MySQL 字符串转义）。
   * 顺序不能反：先把 {@code !} 自身翻倍，否则后补的转义会被再次转义。
   */
  static String likePattern(String raw) {
    String escaped =
        raw.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    return "%" + escaped + "%";
  }
}
