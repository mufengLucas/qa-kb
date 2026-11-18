package com.qa.kb.persistence;

import com.qa.kb.model.TestCaseRow;
import com.qa.kb.model.TestCaseRunExportRow;
import com.qa.kb.model.TestCaseRunRow;
import com.qa.kb.model.TestCaseView;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 用例与执行记录读写（P2 Phase 4）。
 *
 * <p>动态筛选（module / status / q）在 {@code mapper/TestCaseMapper.xml} 里用 {@code <where>}
 * 拼装；自增主键回填同样走 XML。其余语句直接注解即可。
 */
@Mapper
public interface TestCaseMapper {

  String RUN_COLUMNS =
      "r.id, r.case_id, r.result, r.note, r.executor_id, r.executed_at, "
          + "u.login AS executor_login, u.name AS executor_name";

  /* ------------------------------------------------------------------ 用例 */

  @Insert(
      "INSERT INTO test_cases (module, title, precondition, steps, expected, priority, status, creator_id) "
          + "VALUES (#{module}, #{title}, #{precondition}, #{steps}, #{expected}, #{priority}, #{status}, #{creatorId})")
  int insert(
      @Param("module") String module,
      @Param("title") String title,
      @Param("precondition") String precondition,
      @Param("steps") String steps,
      @Param("expected") String expected,
      @Param("priority") String priority,
      @Param("status") String status,
      @Param("creatorId") long creatorId);

  /** 条件计数，SQL 见 XML {@code count}（与 {@code page} 共用同一段 {@code <where>}）。 */
  int count(
      @Param("module") String module,
      @Param("status") String status,
      @Param("pattern") String pattern);

  /** 导出全量命中行（不分页），SQL 见 XML {@code exportCases}。 */
  List<TestCaseView> exportCases(
      @Param("module") String module,
      @Param("status") String status,
      @Param("pattern") String pattern);

  /** 执行记录导出（跨用例全量、按执行 id 升序），SQL 见 XML {@code exportRuns}。 */
  List<TestCaseRunExportRow> exportRuns();

  /** 自增主键回填，SQL 见 XML {@code lastInsertId}。 */
  long lastInsertId();

  /** 分页 + 执行统计，SQL 见 XML {@code page}。 */
  List<TestCaseView> page(
      @Param("module") String module,
      @Param("status") String status,
      @Param("pattern") String pattern,
      @Param("limit") int limit,
      @Param("offset") int offset);

  /** 单条详情，SQL 见 XML {@code findViewById}（与 {@code page} 共用同一段列定义）。 */
  Optional<TestCaseView> findViewById(@Param("id") long id);

  @Update(
      "UPDATE test_cases SET module = #{module}, title = #{title}, precondition = #{precondition}, "
          + "steps = #{steps}, expected = #{expected}, priority = #{priority}, status = #{status} "
          + "WHERE id = #{id}")
  int update(
      @Param("id") long id,
      @Param("module") String module,
      @Param("title") String title,
      @Param("precondition") String precondition,
      @Param("steps") String steps,
      @Param("expected") String expected,
      @Param("priority") String priority,
      @Param("status") String status);

  @Delete("DELETE FROM test_cases WHERE id = #{id}")
  int delete(@Param("id") long id);

  /** 已用模块清单（筛选下拉）；空串代表「未分组」。 */
  @Select("SELECT DISTINCT module FROM test_cases ORDER BY module")
  List<String> listModules();

  /* ---------------------------------------------------------------- 执行记录 */

  @Insert(
      "INSERT INTO test_runs (case_id, result, note, executor_id) "
          + "VALUES (#{caseId}, #{result}, #{note}, #{executorId})")
  int insertRun(
      @Param("caseId") long caseId,
      @Param("result") String result,
      @Param("note") String note,
      @Param("executorId") long executorId);

  @Select(
      "SELECT "
          + RUN_COLUMNS
          + " FROM test_runs r LEFT JOIN users u ON u.id = r.executor_id "
          + "WHERE r.case_id = #{caseId} ORDER BY r.id DESC")
  List<TestCaseRunRow> listRuns(@Param("caseId") long caseId);

  @Select(
      "SELECT "
          + RUN_COLUMNS
          + " FROM test_runs r LEFT JOIN users u ON u.id = r.executor_id WHERE r.id = #{id}")
  Optional<TestCaseRunRow> findRun(@Param("id") long id);

  @Delete("DELETE FROM test_runs WHERE id = #{id}")
  int deleteRun(@Param("id") long id);
}
