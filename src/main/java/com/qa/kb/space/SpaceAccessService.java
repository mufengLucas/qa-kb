package com.qa.kb.space;

import com.qa.kb.auth.Role;
import com.qa.kb.common.ApiException;
import com.qa.kb.model.PageRow;
import com.qa.kb.model.SpaceRow;
import com.qa.kb.persistence.PageMapper;
import com.qa.kb.persistence.SpaceMapper;
import com.qa.kb.persistence.SpaceMemberMapper;
import com.qa.kb.security.SessionUser;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 空间级权限判定（P2 Phase 3）。
 *
 * <p>规则（兼容方案）：
 *
 * <ul>
 *   <li><b>有效角色</b>：有空间成员记录按空间角色，无记录回退全局角色；全局 ADMIN 恒为 ADMIN（不会被
 *       空间角色降级）。反向不成立——全局 VIEWER 是全局只读底线，空间角色不能提升它（SecurityConfig
 *       的方法级规则先于本类拦截写操作）。
 *   <li><b>读</b>：开放空间（restricted=0）登录即可；受限空间需是成员（任意角色）或全局 ADMIN，否则 403。
 *   <li><b>写</b>：先过读校验，再按有效角色 {@code canWrite()}；受限空间非成员连读都不过，自然不可写。
 *   <li><b>管理</b>（改空间设置 / 删空间 / 管理成员）：先过读校验，再按有效角色 {@code canAdmin()}。
 * </ul>
 *
 * <p>列表类端点（空间列表 / 回收站 / 标签 / 搜索）统一用 {@link #visibleSpaceIds} 过滤，避免受限空间
 * 的存在本身泄露给非成员。
 */
public class SpaceAccessService {

  private final SpaceMapper spaceMapper;
  private final SpaceMemberMapper memberMapper;
  private final PageMapper pageMapper;

  public SpaceAccessService(
      SpaceMapper spaceMapper, SpaceMemberMapper memberMapper, PageMapper pageMapper) {
    this.spaceMapper = spaceMapper;
    this.memberMapper = memberMapper;
    this.pageMapper = pageMapper;
  }

  /** 取空间并校验读权限：不存在 404，受限空间非成员 403。 */
  public SpaceRow requireRead(SessionUser user, long spaceId) {
    SpaceRow space =
        spaceMapper.findById(spaceId).orElseThrow(() -> ApiException.notFound("空间不存在"));
    checkRead(user, space);
    return space;
  }

  public void checkRead(SessionUser user, SpaceRow space) {
    if (!Boolean.TRUE.equals(space.restricted()) || user.role().canAdmin()) {
      return;
    }
    if (memberMapper.findRole(space.id(), user.id()).isPresent()) {
      return;
    }
    throw ApiException.forbidden("无权访问该空间");
  }

  public void checkWrite(SessionUser user, SpaceRow space) {
    checkRead(user, space);
    if (!effectiveRole(user, space).canWrite()) {
      throw ApiException.forbidden("无权修改该空间内容");
    }
  }

  public void checkAdmin(SessionUser user, SpaceRow space) {
    checkRead(user, space);
    if (!effectiveRole(user, space).canAdmin()) {
      throw ApiException.forbidden("无权管理该空间");
    }
  }

  /** 读校验（页面级）：按 findAnyById 取归属空间，已软删页面也可通过（恢复前的校验依赖此点）。 */
  public void checkPageRead(SessionUser user, long pageId) {
    checkRead(user, spaceOfPage(pageId));
  }

  /** 写校验（页面级）。 */
  public void checkPageWrite(SessionUser user, long pageId) {
    checkWrite(user, spaceOfPage(pageId));
  }

  /** 有效角色：空间成员记录优先，无记录回退全局角色；全局 ADMIN 恒为 ADMIN。 */
  public Role effectiveRole(SessionUser user, SpaceRow space) {
    if (user.role().canAdmin()) {
      return Role.ADMIN;
    }
    String memberRole = memberMapper.findRole(space.id(), user.id()).orElse(null);
    return memberRole == null ? user.role() : Role.parse(memberRole);
  }

  /** 可见空间 id 集合：开放空间 ∪ 成员空间（全局 ADMIN 经 SQL 参数拿到全部空间）。 */
  public Set<Long> visibleSpaceIds(SessionUser user) {
    return new HashSet<>(memberMapper.visibleSpaceIds(user.id(), user.role().canAdmin() ? 1 : 0));
  }

  public List<SpaceRow> filterReadable(SessionUser user, List<SpaceRow> spaces) {
    if (spaces.isEmpty()) {
      return spaces;
    }
    Set<Long> visible = visibleSpaceIds(user);
    return spaces.stream().filter(s -> visible.contains(s.id())).toList();
  }

  public List<PageRow> filterReadablePages(SessionUser user, List<PageRow> pages) {
    if (pages.isEmpty()) {
      return pages;
    }
    Set<Long> visible = visibleSpaceIds(user);
    return pages.stream().filter(p -> visible.contains(p.spaceId())).toList();
  }

  private SpaceRow spaceOfPage(long pageId) {
    PageRow page =
        pageMapper.findAnyById(pageId).orElseThrow(() -> ApiException.notFound("页面不存在"));
    return spaceMapper.findById(page.spaceId()).orElseThrow(() -> ApiException.notFound("空间不存在"));
  }
}
