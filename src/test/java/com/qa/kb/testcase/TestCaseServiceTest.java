package com.qa.kb.testcase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.kb.common.ApiException;
import com.qa.kb.model.TestCaseRunExportRow;
import com.qa.kb.model.TestCaseRunRow;
import com.qa.kb.model.TestCaseView;
import com.qa.kb.persistence.TestCaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("TestCaseService")
class TestCaseServiceTest {

  @Mock private TestCaseMapper mapper;

  private TestCaseService service;

  @BeforeEach
  void setUp() {
    service = new TestCaseService(mapper);
  }

  private static TestCaseView view(long id) {
    LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 0);
    return new TestCaseView(
        id,
        "登录",
        "正常登录",
        "已有账号",
        "1. 打开登录页\n2. 输入账号密码",
        "进入首页",
        "P1",
        "ACTIVE",
        1L,
        "admin",
        "管理员",
        at,
        at,
        3,
        2,
        1,
        "PASS",
        at);
  }

  private static TestCaseView viewOf(long id, String title) {
    LocalDateTime at = LocalDateTime.of(2026, 11, 12, 10, 0);
    return new TestCaseView(
        id, "支付", title, "p", "s", "e", "P0", "ARCHIVED", 1L, "admin", "管理员", at, at,
        0, 0, 0, null, null);
  }

  private static TestCaseRunRow run(long id, long caseId, String result) {
    return new TestCaseRunRow(
        id, caseId, result, "", 1L, "admin", "管理员", LocalDateTime.of(2026, 11, 12, 11, 0));
  }

  /* ------------------------------------------------------------------ 用例 */

  @Test
  @DisplayName("创建：priority/status 缺省 P2/ACTIVE，返回落库后的视图")
  void createAppliesDefaultsAndReturnsPersistedView() {
    when(mapper.insert(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong()))
        .thenReturn(1);
    when(mapper.lastInsertId()).thenReturn(7L);
    when(mapper.findViewById(7L)).thenReturn(Optional.of(view(7L)));

    TestCaseView created =
        service.create("登录", "正常登录", null, null, null, null, null, 1L);

    assertThat(created.id()).isEqualTo(7L);
    assertThat(created.title()).isEqualTo("正常登录");
    verify(mapper)
        .insert("登录", "正常登录", "", "", "", "P2", "ACTIVE", 1L);
  }

  @Test
  @DisplayName("创建：标题必填，空白直接 400 且不落库")
  void createRejectsBlankTitle() {
    assertThatThrownBy(() -> service.create("登录", "  ", null, null, null, null, null, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("用例标题");

    verify(mapper, never())
        .insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong());
  }

  @Test
  @DisplayName("创建：未知优先级 / 未知状态 / 标题超长均 400")
  void createRejectsInvalidInput() {
    assertThatThrownBy(() -> service.create("m", "t", null, null, null, "P9", null, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("优先级");
    assertThatThrownBy(() -> service.create("m", "t", null, null, null, null, "RUNNING", 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("状态");
    assertThatThrownBy(
            () -> service.create("m", "x".repeat(TestCaseService.MAX_TITLE_LENGTH + 1), null, null,
                null, null, null, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("用例标题");

    verify(mapper, never())
        .insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong());
  }

  @Test
  @DisplayName("查询：缺省 limit=50 / offset=0，module 与 status 为空串表示不过滤")
  void listUsesPagingDefaults() {
    when(mapper.count("", "", null)).thenReturn(0);
    when(mapper.page("", "", null, 50, 0)).thenReturn(List.of());

    TestCaseService.CasePage page = service.list(null, null, null, null, null);

    assertThat(page.total()).isZero();
    assertThat(page.items()).isEmpty();
  }

  @Test
  @DisplayName("查询：limit 夹取到 [1,200]，offset 不为负")
  void listClampsPaging() {
    when(mapper.count("登录", "ACTIVE", null)).thenReturn(1);
    when(mapper.page("登录", "ACTIVE", null, 200, 0)).thenReturn(List.of(view(1L)));
    when(mapper.listModules()).thenReturn(List.of("登录"));

    TestCaseService.CasePage page = service.list("登录", null, "active", 9999, -5);

    assertThat(page.total()).isEqualTo(1);
    assertThat(page.modules()).containsExactly("登录");
  }

  @Test
  @DisplayName("查询：关键字通配符被转义，% 与 _ 不再变成全表匹配")
  void listEscapesLikeWildcards() {
    assertThat(TestCaseService.likePattern("a!b%c_d")).isEqualTo("%a!!b!%c!_d%");
    assertThat(TestCaseService.likePattern("  100%  ")).isEqualTo("%100!%%");

    when(mapper.count("", "", "%100!%%")).thenReturn(1);
    when(mapper.page("", "", "%100!%%", 50, 0)).thenReturn(List.of());

    service.list(null, "100%", null, null, null);

    verify(mapper).count("", "", "%100!%%");
  }

  @Test
  @DisplayName("查询：未知状态筛选值 400")
  void listRejectsUnknownStatus() {
    assertThatThrownBy(() -> service.list(null, null, "nope", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("状态");
  }

  @Test
  @DisplayName("详情/删除：用例不存在 404")
  void missingCaseIsNotFound() {
    when(mapper.findViewById(404L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.get(404L))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("用例不存在");
    assertThatThrownBy(() -> service.delete(404L))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("用例不存在");
    verify(mapper, never()).delete(anyLong());
  }

  @Test
  @DisplayName("更新：先校验存在再落库，用不存在的 id 直接 404")
  void updateRequiresExistingCase() {
    // update 内部两次读库：第一次校验存在，第二次返回落库后的视图
    when(mapper.findViewById(9L))
        .thenReturn(Optional.of(view(9L)), Optional.of(viewOf(9L, "改名后的用例")));

    TestCaseView updated =
        service.update(9L, "支付", "改名后的用例", "p", "s", "e", "P0", "ARCHIVED");

    assertThat(updated.title()).isEqualTo("改名后的用例");
    verify(mapper).update(9L, "支付", "改名后的用例", "p", "s", "e", "P0", "ARCHIVED");

    when(mapper.findViewById(404L)).thenReturn(Optional.empty());
    assertThatThrownBy(
            () -> service.update(404L, "m", "t", null, null, null, null, null))
        .isInstanceOf(ApiException.class);
    verify(mapper, never())
        .update(eq(404L), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString());
  }

  /* ------------------------------------------------------------ 执行记录 */

  @Test
  @DisplayName("记录执行：结果统一大写归一，用例不存在 404")
  void addRunNormalizesResultAndChecksCase() {
    when(mapper.findViewById(3L)).thenReturn(Optional.of(view(3L)));
    when(mapper.insertRun(3L, "PASS", "冒烟通过", 5L)).thenReturn(1);
    when(mapper.lastInsertId()).thenReturn(11L);
    when(mapper.findRun(11L)).thenReturn(Optional.of(run(11L, 3L, "PASS")));

    TestCaseRunRow created = service.addRun(3L, "pass", "冒烟通过", 5L);

    assertThat(created.result()).isEqualTo("PASS");
    verify(mapper).insertRun(3L, "PASS", "冒烟通过", 5L);

    when(mapper.findViewById(404L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.addRun(404L, "PASS", null, 5L))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("用例不存在");
  }

  @Test
  @DisplayName("记录执行：空结果与未知结果均 400，且不落库")
  void addRunRejectsBadResult() {
    when(mapper.findViewById(1L)).thenReturn(Optional.of(view(1L)));

    assertThatThrownBy(() -> service.addRun(1L, "  ", null, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("执行结果");
    assertThatThrownBy(() -> service.addRun(1L, "MAYBE", null, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("执行结果");

    verify(mapper, never()).insertRun(anyLong(), anyString(), anyString(), anyLong());
  }

  @Test
  @DisplayName("执行记录：用例列表先校验用例存在")
  void listRunsChecksCaseFirst() {
    when(mapper.findViewById(1L)).thenReturn(Optional.of(view(1L)));
    when(mapper.listRuns(1L)).thenReturn(List.of(run(1L, 1L, "FAIL")));

    assertThat(service.listRuns(1L)).hasSize(1);

    when(mapper.findViewById(404L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.listRuns(404L)).isInstanceOf(ApiException.class);
  }

  @Test
  @DisplayName("删除执行记录：不属于该用例 404，避免跨用例误删")
  void deleteRunRejectsForeignRun() {
    when(mapper.findRun(5L)).thenReturn(Optional.of(run(5L, 2L, "PASS")));

    assertThatThrownBy(() -> service.deleteRun(1L, 5L))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("执行记录不存在");
    verify(mapper, never()).deleteRun(anyLong());

    service.deleteRun(2L, 5L);
    verify(mapper).deleteRun(5L);
  }

  /* ------------------------------------------------------------ 导入导出（A1） */

  @Test
  @DisplayName("导出用例：与列表共用同一套筛选清洗，命中全量")
  void exportCasesReusesFilters() {
    when(mapper.exportCases("支付", "ACTIVE", "%100!%%"))
        .thenReturn(List.of(view(1L), view(2L)));

    List<TestCaseView> rows = service.exportCases("支付", "100%", "active");

    assertThat(rows).hasSize(2);
    verify(mapper).exportCases("支付", "ACTIVE", "%100!%%");
    // 导出非法筛选值同样 400（与列表一致）
    assertThatThrownBy(() -> service.exportCases(null, null, "nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("状态");
  }

  @Test
  @DisplayName("导出执行：跨用例全量，直接透传 mapper")
  void exportRunsDelegates() {
    TestCaseRunExportRow row =
        new TestCaseRunExportRow(
            "登录", "正常登录", "PASS", "", "admin", "管理员", LocalDateTime.of(2026, 11, 12, 11, 0));
    when(mapper.exportRuns()).thenReturn(List.of(row));

    assertThat(service.exportRuns()).containsExactly(row);
  }

  @Test
  @DisplayName("导入：表头按列名映射（顺序不限），合法行逐条落库")
  void importMapsHeaderByColumnName() {
    when(mapper.insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong()))
        .thenReturn(1);
    when(mapper.lastInsertId()).thenReturn(1L, 2L);
    when(mapper.findViewById(1L)).thenReturn(Optional.of(view(1L)));
    when(mapper.findViewById(2L)).thenReturn(Optional.of(view(2L)));

    // 列顺序与服务端不同、还夹着未知列；两行顺序颠倒也照常映射
    String csv =
        "status,title,module,priority\n"
            + "ACTIVE,用例甲,登录,P1\n"
            + "ARCHIVED,用例乙,支付,P0\n";

    TestCaseService.ImportResult result = service.importCases(csv, 9L);

    assertThat(result.created()).isEqualTo(2);
    assertThat(result.errors()).isEmpty();
    verify(mapper).insert("登录", "用例甲", "", "", "", "P1", "ACTIVE", 9L);
    verify(mapper).insert("支付", "用例乙", "", "", "", "P0", "ARCHIVED", 9L);
  }

  @Test
  @DisplayName("导入：坏行收集回执不整体失败，行号含表头")
  void importCollectsRowErrorsWithoutFailing() {
    when(mapper.insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong()))
        .thenReturn(1);
    when(mapper.lastInsertId()).thenReturn(1L);
    when(mapper.findViewById(1L)).thenReturn(Optional.of(view(1L)));

    String csv =
        "module,title,priority\n"
            + "登录,好用例,P2\n"
            + ",  ,P2\n" // 空标题 → 第 3 行报错
            + "支付,坏优先级,P9\n"; // 未知优先级 → 第 4 行报错

    TestCaseService.ImportResult result = service.importCases(csv, 1L);

    assertThat(result.created()).isEqualTo(1);
    assertThat(result.errors()).hasSize(2);
    assertThat(result.errors().get(0).row()).isEqualTo(3);
    assertThat(result.errors().get(0).message()).contains("用例标题");
    assertThat(result.errors().get(1).row()).isEqualTo(4);
    assertThat(result.errors().get(1).message()).contains("优先级");
    verify(mapper, never())
        .insert(eq("支付"), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong());
  }

  @Test
  @DisplayName("导入：缺 title 表头 / 空内容 / 超行数一律整体 400")
  void importRejectsBadEnvelope() {
    assertThatThrownBy(() -> service.importCases("module,priority\n登录,P1\n", 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("title");
    assertThatThrownBy(() -> service.importCases("", 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("为空");

    StringBuilder big = new StringBuilder("title\n");
    for (int i = 0; i <= TestCaseService.MAX_IMPORT_ROWS; i++) {
      big.append("用例").append(i).append('\n');
    }
    assertThatThrownBy(() -> service.importCases(big.toString(), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("最多");
    verify(mapper, never())
        .insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong());
  }

  @Test
  @DisplayName("导入：BOM 表头与缺省列（缺列按空值走服务端默认）")
  void importStripsBomAndDefaultsMissingColumns() {
    when(mapper.insert(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyLong()))
        .thenReturn(1);
    when(mapper.lastInsertId()).thenReturn(1L);
    when(mapper.findViewById(1L)).thenReturn(Optional.of(view(1L)));

    TestCaseService.ImportResult result =
        service.importCases("\uFEFFtitle\n只有标题一行\n", 3L);

    assertThat(result.created()).isEqualTo(1);
    assertThat(result.errors()).isEmpty();
    verify(mapper).insert("", "只有标题一行", "", "", "", "P2", "ACTIVE", 3L);
  }
}
