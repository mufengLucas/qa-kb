package com.qa.kb.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** RFC 4180 解析/序列化的核心路径：引号、字段内换行、BOM、转义、未闭合报错。 */
@DisplayName("Csv")
class CsvTest {

  @Test
  @DisplayName("解析：逗号分隔的基本行，CRLF 与 LF 都认")
  void parsesPlainRows() {
    assertThat(Csv.parse("a,b,c\r\n1,2,3")).containsExactly(List.of("a", "b", "c"), List.of("1", "2", "3"));
    assertThat(Csv.parse("a,b\n1,2")).containsExactly(List.of("a", "b"), List.of("1", "2"));
  }

  @Test
  @DisplayName("解析：引号字段里的逗号 / 换行 / 双引号转义")
  void parsesQuotedFields() {
    assertThat(Csv.parse("\"a,b\",c")).containsExactly(List.of("a,b", "c"));
    assertThat(Csv.parse("\"line1\nline2\",x"))
        .as("引号内换行属于同一字段，不拆行")
        .containsExactly(List.of("line1\nline2", "x"));
    assertThat(Csv.parse("\"say \"\"hi\"\"\",y")).containsExactly(List.of("say \"hi\"", "y"));
  }

  @Test
  @DisplayName("解析：BOM 剥离，不污染首列表头")
  void stripsBom() {
    List<List<String>> records = Csv.parse("\uFEFFmodule,title\n登录,正常登录");
    assertThat(records.get(0)).containsExactly("module", "title");
  }

  @Test
  @DisplayName("解析：引号未闭合抛 IAE（全局处理器转 400）")
  void rejectsUnclosedQuote() {
    assertThatThrownBy(() -> Csv.parse("\"unterminated,title"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("引号未闭合");
  }

  @Test
  @DisplayName("解析：空串返回空记录；尾行无换行也收进来")
  void handlesEmptyAndTrailingRow() {
    assertThat(Csv.parse("")).isEmpty();
    assertThat(Csv.parse("a,b")).containsExactly(List.of("a", "b"));
  }

  @Test
  @DisplayName("序列化：普通字段不加引号，特殊字段按需加引号")
  void serializesWithEscaping() {
    assertThat(Csv.row("a", "b")).isEqualTo("a,b");
    assertThat(Csv.row("a,b", "c")).isEqualTo("\"a,b\",c");
    assertThat(Csv.row("say \"hi\"", "x")).isEqualTo("\"say \"\"hi\"\"\",x");
    assertThat(Csv.row("l1\nl2", "x")).isEqualTo("\"l1\nl2\",x");
    assertThat(Csv.row(null, "")).isEqualTo(",");
  }

  @Test
  @DisplayName("往返：序列化再解析还原原值")
  void roundTrips() {
    List<String> cells = List.of("登录,模块", "含\"引号\"", "含\n换行", "", "普通");
    assertThat(Csv.parse(Csv.row(cells))).containsExactly(cells);
  }
}
