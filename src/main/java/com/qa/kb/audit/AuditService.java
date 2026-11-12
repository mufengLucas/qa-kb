package com.qa.kb.audit;

import com.qa.kb.model.AuditRow;
import com.qa.kb.persistence.AuditMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 操作审计服务（P2 Phase 2）。
 *
 * <p>{@link #record} 是写入的唯一入口，内部捕获所有异常并只记 warn 日志——审计失败绝不能影响
 * 主业务请求的响应（拦截器在 afterCompletion 阶段调用，此时响应可能已提交）。
 */
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  /** action/target 截断长度，与 schema 列宽对齐（action 200 / target_type 50 / target_id 100）。 */
  private static final int MAX_ACTION = 200;

  private static final int MAX_TARGET_TYPE = 50;
  private static final int MAX_TARGET_ID = 100;
  private static final int MAX_DETAIL = 500;

  private final AuditMapper auditMapper;

  public AuditService(AuditMapper auditMapper) {
    this.auditMapper = auditMapper;
  }

  /** 记一条审计。永不抛出异常；userId 可为 null（匿名或登录前操作）。 */
  public void record(
      Long userId, String action, String targetType, String targetId, String detail) {
    try {
      auditMapper.insert(
          userId,
          cut(action, MAX_ACTION),
          cut(targetType, MAX_TARGET_TYPE),
          cut(targetId, MAX_TARGET_ID),
          cut(detail == null ? "" : detail, MAX_DETAIL));
    } catch (RuntimeException e) {
      log.warn("审计写入失败 action={} target={}/{}: {}", action, targetType, targetId, e.getMessage());
    }
  }

  /** 分页读取（最新在前）。供 ADMIN 审计页使用。 */
  public List<AuditRow> page(int limit, int offset) {
    int safeLimit = Math.min(Math.max(limit, 1), 200);
    int safeOffset = Math.max(offset, 0);
    return auditMapper.page(safeLimit, safeOffset);
  }

  public long count() {
    return auditMapper.count();
  }

  private static String cut(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
