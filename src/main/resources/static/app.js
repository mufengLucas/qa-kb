/* QA 团队工作台 —— 无构建步骤的 Vue 3 单页应用 */
(function () {
  "use strict";

  const { createApp } = Vue;

  const TASK_STATUS = {
    todo: "待开始",
    doing: "进行中",
    done: "已完成",
    blocked: "阻塞",
  };

  const TASK_STATUS_OPTIONS = ["todo", "doing", "done", "blocked"];

  const PROJECT_STATUS = {
    PLANNING: "规划中",
    ACTIVE: "进行中",
    DONE: "已完成",
    ARCHIVED: "已归档",
  };

  const PROJECT_STATUS_OPTIONS = ["PLANNING", "ACTIVE", "DONE", "ARCHIVED"];

  const PRIORITY = { 1: "低", 2: "中", 3: "高", 4: "紧急" };

  function pad(n) {
    return String(n).padStart(2, "0");
  }

  function isoDate(date) {
    const d = date || new Date();
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate());
  }

  /** 返回给定日期所在周的周一（周报以周一为周起始）。 */
  function mondayOf(date) {
    const d = date || new Date();
    const offset = (d.getDay() + 6) % 7; // 周一=0 … 周日=6
    d.setDate(d.getDate() - offset);
    return isoDate(d);
  }

  function getCookie(name) {
    const parts = ("; " + document.cookie).split("; " + name + "=");
    return parts.length === 2 ? parts.pop().split(";").shift() : null;
  }

  let csrfToken = null;

  function cookieCsrfToken() {
    const raw = getCookie("XSRF-TOKEN");
    if (!raw) {
      return null;
    }
    try {
      return decodeURIComponent(raw);
    } catch (ignored) {
      return raw;
    }
  }

  async function fetchCsrfToken() {
    const response = await fetch("/api/auth/csrf", { credentials: "same-origin" });
    if (!response.ok) {
      return null;
    }
    const data = await response.json();
    csrfToken = data && data.token ? data.token : null;
    return csrfToken;
  }

  async function ensureCsrf() {
    if (csrfToken) {
      return csrfToken;
    }
    csrfToken = cookieCsrfToken();
    if (csrfToken) {
      return csrfToken;
    }
    try {
      return await fetchCsrfToken();
    } catch (ignored) {
      return null; /* 离线时由后续请求给出真实错误 */
    }
  }

  async function api(method, url, body, retried) {
    const upper = (method || "GET").toUpperCase();
    const mutating = upper !== "GET" && upper !== "HEAD";
    const headers = {};
    if (body !== undefined) {
      headers["Content-Type"] = "application/json; charset=utf-8";
    }
    if (mutating) {
      const token = await ensureCsrf();
      if (token) {
        headers["X-XSRF-TOKEN"] = token;
      }
    }
    const response = await fetch(url, {
      method: upper,
      headers: headers,
      credentials: "same-origin",
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (mutating && response.status === 403 && !retried) {
      // A stale or missing CSRF token is reported as 403. Refresh it once and retry so a token
      // rotation never looks like a permission problem.
      try {
        await response.text();
      } catch (ignored) {
        /* the body is irrelevant */
      }
      csrfToken = null;
      try {
        await fetchCsrfToken();
      } catch (ignored) {
        /* fall through: the retry will surface the real error */
      }
      return api(method, url, body, true);
    }
    if (response.status === 204) {
      return null;
    }
    const text = await response.text();
    let data = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch (ignored) {
        data = text;
      }
    }
    if (!response.ok) {
      const message =
        (data && (data.message || data.error)) || "请求失败（HTTP " + response.status + "）";
      const error = new Error(message);
      error.status = response.status;
      error.payload = data;
      throw error;
    }
    return data;
  }

  function renderMarkdown(source) {
    const text = source || "";
    if (!window.marked) {
      return text;
    }
    try {
      const html = window.marked.parse(text, { gfm: true, breaks: true });
      return window.DOMPurify ? window.DOMPurify.sanitize(html) : html;
    } catch (ignored) {
      return text;
    }
  }

  /* ------------------------------------------------------------------ 递归页面树 */
  const PageTreeNode = {
    name: "PageTreeNode",
    props: {
      node: { type: Object, required: true },
      activeId: { type: [Number, String], default: null },
    },
    emits: ["open"],
    template: `
      <div class="tree-item">
        <button
          type="button"
          class="tree-row"
          :class="{ active: node.id === activeId }"
          :title="node.title"
          @click="$emit('open', node.id)"
        >{{ node.title }}</button>
        <div v-if="node.children && node.children.length" class="tree-children">
          <page-tree-node
            v-for="child in node.children"
            :key="child.id"
            :node="child"
            :active-id="activeId"
            @open="id => $emit('open', id)"
          ></page-tree-node>
        </div>
      </div>
    `,
  };

  const App = {
    data() {
      return {
        user: null,
        booting: true,
        loginForm: { login: "", password: "" },
        loginBusy: false,
        tab: new URLSearchParams(window.location.search).get("tab") || "kb",
        notice: null,
        _noticeTimer: null,

        /* 知识库 */
        spaces: [],
        spaceId: null,
        tree: [],
        activePageId: null,
        current: null,
        pageTags: [],
        editing: false,
        editor: {
          id: null,
          parentId: null,
          title: "",
          contentMd: "",
          status: "PUBLISHED",
          tagsText: "",
        },
        showTrash: false,
        trash: [],
        showRevisions: false,
        revisions: [],
        showSpaceForm: false,
        showSpaceManage: false,
        spaceBusy: false,
        spaceForm: { name: "", slug: "" },
        searchQ: "",
        hits: [],
        searchBusy: false,

        /* 周报 */
        weekStart: mondayOf(),
        report: { done: "", plan: "", blockers: "", status: "draft" },
        linkedProjectIds: [],
        teamReports: [],
        missingIds: [],
        items: [],
        newItemTitle: "",
        reportBusy: false,

        /* 项目 */
        projects: [],
        projectDetail: null,
        showNewProject: false,
        newProject: {
          name: "",
          projectKey: "",
          status: "ACTIVE",
          startDate: "",
          endDate: "",
          description: "",
        },
        members: [],
        showNewTask: false,
        newTask: {
          title: "",
          priority: 2,
          status: "todo",
          startDate: "",
          dueDate: "",
          milestoneId: "",
          assigneeId: "",
        },
        showNewMilestone: false,
        newMilestone: { name: "", dueDate: "" },

        /* 成员 */
        users: [],
        newUser: { login: "", name: "", password: "", role: "MEMBER" },
        showNewUser: false,
        backupBusy: false,
        lastBackup: null,
      };
    },

    computed: {
      isAdmin() {
        return !!this.user && this.user.role === "ADMIN";
      },
      canWrite() {
        return !!this.user && (this.user.role === "ADMIN" || this.user.role === "MEMBER");
      },
      editorPreview() {
        return renderMarkdown(this.editor.contentMd);
      },
      currentSpace() {
        const id = this.spaceId;
        return this.spaces.find((s) => s.id === id) || null;
      },
      renderedContent() {
        return this.current ? renderMarkdown(this.current.contentMd) : "";
      },
      flatTree() {
        const out = [];
        const walk = (nodes, depth) => {
          (nodes || []).forEach((node) => {
            out.push({ id: node.id, title: node.title, depth: depth });
            walk(node.children, depth + 1);
          });
        };
        walk(this.tree, 0);
        return out;
      },
      missingNames() {
        return this.missingIds.map((id) => {
          const found = this.users.find((u) => u.id === id);
          return found ? found.name : "#" + id;
        });
      },
      /** 本周一（「本周」按钮用）。 */
      mondayOfToday() {
        return mondayOf();
      },
      /** 当前周的周日（周区间显示）。 */
      weekEnd() {
        const parts = this.weekStart.split("-").map(Number);
        const d = new Date(parts[0], parts[1] - 1, parts[2]);
        d.setDate(d.getDate() + 6);
        return isoDate(d);
      },
      doneItems() {
        return this.items.filter((item) => item.done);
      },
      pendingItems() {
        return this.items.filter((item) => !item.done);
      },
      doneCount() {
        return this.doneItems.length;
      },
    },

    watch: {
      searchQ(value) {
        clearTimeout(this._searchTimer);
        const q = (value || "").trim();
        if (!q) {
          this.hits = [];
          return;
        }
        this._searchTimer = setTimeout(() => this.runSearch(q), 250);
      },
      weekStart() {
        this.loadReport();
      },
    },

    mounted() {
      this.boot();
    },

    methods: {
      /* ---------------------------------------------------------------- 基础 */
      toast(text, type) {
        this.notice = { text: text, type: type || "ok" };
        clearTimeout(this._noticeTimer);
        this._noticeTimer = setTimeout(() => {
          this.notice = null;
        }, 3200);
      },
      fail(error) {
        if (error && error.status === 401) {
          this.user = null;
          return;
        }
        if (error && error.status === 403) {
          this.toast("没有权限执行该操作；若刚切换过账号，请刷新页面后重试", "error");
          return;
        }
        this.toast((error && error.message) || "操作失败", "error");
      },
      async boot() {
        this.booting = true;
        await ensureCsrf();
        try {
          this.user = await api("GET", "/api/auth/me");
        } catch (error) {
          this.user = null;
        }
        this.booting = false;
        if (this.user) {
          await this.afterLogin();
        }
      },
      async afterLogin() {
        this.loginBusy = false;
        this.loginForm.password = "";
        await this.loadSpaces();
        await this.loadProjects();
        if (this.isAdmin) {
          await this.loadUsers();
        }
        await this.loadReport();
      },
      async doLogin() {
        const login = (this.loginForm.login || "").trim();
        if (!login || !this.loginForm.password) {
          this.toast("请输入登录名和密码", "error");
          return;
        }
        this.loginBusy = true;
        try {
          this.user = await api("POST", "/api/auth/login", {
            login: login,
            password: this.loginForm.password,
          });
          await this.afterLogin();
        } catch (error) {
          this.loginBusy = false;
          this.toast(error.message || "登录失败", "error");
        }
      },
      async doLogout() {
        try {
          await api("POST", "/api/auth/logout");
        } catch (ignored) {
          /* 会话可能已过期 */
        }
        csrfToken = null;
        this.user = null;
        this.current = null;
        this.activePageId = null;
        this.tree = [];
        this.spaces = [];
      },

      /* ---------------------------------------------------------------- 知识库 */
      async loadSpaces() {
        try {
          this.spaces = (await api("GET", "/api/spaces")) || [];
          if (this.spaces.length && !this.spaces.some((s) => s.id === this.spaceId)) {
            this.spaceId = this.spaces[0].id;
          } else if (!this.spaces.length) {
            // 最后一个空间也被删除时必须清空，否则会带着已删 id 请求树接口 → 404
            this.spaceId = null;
            this.tree = [];
          }
          if (this.spaceId) {
            await this.loadTree();
          }
        } catch (error) {
          this.fail(error);
        }
      },
      async loadTree() {
        if (!this.spaceId) {
          this.tree = [];
          return;
        }
        try {
          this.tree = (await api("GET", "/api/spaces/" + this.spaceId + "/pages/tree")) || [];
        } catch (error) {
          this.fail(error);
        }
      },
      async changeSpace() {
        this.current = null;
        this.activePageId = null;
        this.editing = false;
        this.showTrash = false;
        this.showRevisions = false;
        await this.loadTree();
      },
      async openPage(id) {
        this.showTrash = false;
        this.showRevisions = false;
        this.editing = false;
        this.activePageId = id;
        try {
          this.current = await api("GET", "/api/pages/" + id);
          this.pageTags = (await api("GET", "/api/pages/" + id + "/tags")) || [];
        } catch (error) {
          this.fail(error);
        }
      },
      async openHit(hit) {
        if (hit.spaceId !== this.spaceId) {
          this.spaceId = hit.spaceId;
          await this.loadTree();
        }
        await this.openPage(hit.id);
        this.searchQ = "";
        this.hits = [];
      },
      async runSearch(q) {
        this.searchBusy = true;
        try {
          const params = new URLSearchParams({ q: q });
          if (this.spaceId) {
            params.set("spaceId", String(this.spaceId));
          }
          this.hits = (await api("GET", "/api/search?" + params.toString())) || [];
        } catch (error) {
          this.fail(error);
        } finally {
          this.searchBusy = false;
        }
      },
      startNew(parentId) {
        this.showTrash = false;
        this.showRevisions = false;
        this.editing = true;
        this.editor = {
          id: null,
          parentId: parentId == null ? null : parentId,
          title: "",
          contentMd: "",
          status: "PUBLISHED",
          tagsText: "",
        };
      },
      startEdit() {
        if (!this.current) {
          return;
        }
        this.editing = true;
        this.showRevisions = false;
        this.editor = {
          id: this.current.id,
          parentId: this.current.parentId,
          title: this.current.title,
          contentMd: this.current.contentMd || "",
          status: this.current.status || "PUBLISHED",
          tagsText: this.pageTags.map((t) => t.name).join(", "),
        };
      },
      cancelEdit() {
        this.editing = false;
      },
      async savePage() {
        const title = (this.editor.title || "").trim();
        if (!title) {
          this.toast("标题不能为空", "error");
          return;
        }
        const payload = {
          title: title,
          contentMd: this.editor.contentMd || "",
          status: this.editor.status || "PUBLISHED",
        };
        try {
          let saved;
          if (this.editor.id) {
            saved = await api("PUT", "/api/pages/" + this.editor.id, payload);
          } else {
            saved = await api("POST", "/api/spaces/" + this.spaceId + "/pages", {
              parentId: this.editor.parentId,
              title: payload.title,
              contentMd: payload.contentMd,
              status: payload.status,
            });
          }
          const tags = (this.editor.tagsText || "")
            .split(",")
            .map((tag) => tag.trim())
            .filter(Boolean);
          await api("PUT", "/api/pages/" + saved.id + "/tags", { tags: tags });
          this.editing = false;
          await this.loadTree();
          await this.openPage(saved.id);
          this.toast("已保存", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async deletePage() {
        if (!this.current) {
          return;
        }
        if (!window.confirm("删除《" + this.current.title + "》？可在回收站恢复。")) {
          return;
        }
        try {
          await api("DELETE", "/api/pages/" + this.current.id);
          this.current = null;
          this.activePageId = null;
          await this.loadTree();
          this.toast("已移入回收站", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async openTrash() {
        this.editing = false;
        this.showRevisions = false;
        this.current = null;
        this.activePageId = null;
        try {
          this.trash = (await api("GET", "/api/pages/trash")) || [];
          this.showTrash = true;
        } catch (error) {
          this.fail(error);
        }
      },
      async restorePage(id) {
        try {
          await api("POST", "/api/pages/" + id + "/restore");
          this.trash = (await api("GET", "/api/pages/trash")) || [];
          await this.loadTree();
          this.toast("已恢复", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async openRevisions() {
        if (!this.current) {
          return;
        }
        try {
          this.revisions = (await api("GET", "/api/pages/" + this.current.id + "/revisions")) || [];
          this.showRevisions = true;
        } catch (error) {
          this.fail(error);
        }
      },
      async rollback(revision) {
        if (!window.confirm("回滚到 " + revision.createdAt + " 的版本？当前内容会先存档。")) {
          return;
        }
        try {
          this.current = await api(
            "POST",
            "/api/pages/" + this.current.id + "/rollback/" + revision.id
          );
          this.showRevisions = false;
          await this.loadTree();
          this.toast("已回滚", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async createSpace() {
        const name = (this.spaceForm.name || "").trim();
        if (!name) {
          this.toast("请输入空间名称", "error");
          return;
        }
        try {
          const created = await api("POST", "/api/spaces", {
            name: name,
            slug: (this.spaceForm.slug || "").trim() || null,
            icon: "",
            description: "",
            sort: this.spaces.length,
          });
          this.spaceForm = { name: "", slug: "" };
          this.showSpaceForm = false;
          await this.loadSpaces();
          this.spaceId = created.id;
          await this.loadTree();
          this.toast("空间已创建", "ok");
        } catch (error) {
          this.fail(error);
        }
      },

      /** 删除空间：硬删除，级联清掉全部页面（不可恢复），与页面回收站不同。 */
      async deleteSpace(space) {
        if (!space || this.spaceBusy) {
          return;
        }
        if (
          !window.confirm(
            "删除空间《" +
              space.name +
              "》？该空间下的所有页面、修订与标签会一并永久删除（硬删除，不进回收站，无法恢复）。"
          )
        ) {
          return;
        }
        this.spaceBusy = true;
        try {
          await api("DELETE", "/api/spaces/" + space.id);
          if (this.spaceId === space.id) {
            // 删的是当前空间：清掉已打开的页面，loadSpaces 会切到剩余第一个空间
            this.current = null;
            this.activePageId = null;
            this.editing = false;
            this.showTrash = false;
            this.showRevisions = false;
          }
          await this.loadSpaces();
          this.toast("空间已删除", "ok");
        } catch (error) {
          this.fail(error);
        } finally {
          this.spaceBusy = false;
        }
      },

      /* ---------------------------------------------------------------- 周报 */
      async loadReport() {
        this.reportBusy = true;
        const blank = { done: "", plan: "", blockers: "", status: "draft" };
        try {
          const mine = await api("GET", "/api/reports/weekly/" + this.weekStart);
          this.report = mine
            ? {
                done: mine.done || "",
                plan: mine.plan || "",
                blockers: mine.blockers || "",
                status: mine.status || "draft",
              }
            : { ...blank };
        } catch (error) {
          if (error.status === 404) {
            this.report = { ...blank };
          } else {
            this.fail(error);
          }
        }
        try {
          this.linkedProjectIds =
            (await api("GET", "/api/reports/weekly/" + this.weekStart + "/projects")) || [];
        } catch (error) {
          this.linkedProjectIds = [];
          if (error.status !== 404) {
            this.fail(error);
          }
        }
        try {
          this.items = (await api("GET", "/api/reports/weekly/" + this.weekStart + "/items")) || [];
        } catch (error) {
          this.items = [];
          if (error.status !== 404) {
            this.fail(error);
          }
        }
        await this.loadTeamReports();
        this.reportBusy = false;
      },
      async loadTeamReports() {
        try {
          this.teamReports =
            (await api("GET", "/api/reports/team?week=" + this.weekStart)) || [];
          if (this.isAdmin) {
            this.missingIds =
              (await api("GET", "/api/reports/missing?week=" + this.weekStart)) || [];
          }
        } catch (error) {
          this.fail(error);
        }
      },
      async saveReport(status) {
        this.report.status = status || this.report.status || "draft";
        // 提交前剪枝：剔除已删除/不存在的项目关联，避免后端 400（服务端对失效关联同样拦截）
        const aliveIds = new Set((this.projects || []).map((p) => p.id));
        const projectIds = aliveIds.size
          ? this.linkedProjectIds.filter((id) => aliveIds.has(id))
          : this.linkedProjectIds;
        const dropped = this.linkedProjectIds.length - projectIds.length;
        if (dropped > 0) {
          this.linkedProjectIds = projectIds;
          this.toast("已清除 " + dropped + " 项失效的项目关联", "ok");
        }
        this.reportBusy = true;
        try {
          await api("PUT", "/api/reports/weekly/" + this.weekStart, {
            done: this.report.done || "",
            plan: this.report.plan || "",
            blockers: this.report.blockers || "",
            status: this.report.status,
            projectIds: projectIds,
          });
          this.toast(this.report.status === "submitted" ? "周报已提交" : "草稿已保存", "ok");
          await this.loadReport();
        } catch (error) {
          this.fail(error);
        } finally {
          this.reportBusy = false;
        }
      },
      shiftWeek(weeks) {
        const parts = this.weekStart.split("-").map(Number);
        const d = new Date(parts[0], parts[1] - 1, parts[2]);
        d.setDate(d.getDate() + weeks * 7);
        this.weekStart = mondayOf(d);
      },

      /* ---------------------------------------------------------- 周报清单 */
      async addItem() {
        const title = (this.newItemTitle || "").trim();
        if (!title) {
          return;
        }
        this.reportBusy = true;
        try {
          const created = await api(
            "POST",
            "/api/reports/weekly/" + this.weekStart + "/items",
            { title: title }
          );
          if (created) {
            this.items.push(created);
          }
          this.newItemTitle = "";
        } catch (error) {
          this.fail(error);
        } finally {
          this.reportBusy = false;
        }
      },
      async toggleItem(item, event) {
        const next = !item.done;
        item.done = next; // 乐观更新，失败回滚
        this.reportBusy = true;
        try {
          const updated = await api("PATCH", "/api/reports/items/" + item.id, {
            done: next,
          });
          if (updated) {
            Object.assign(item, updated);
          }
          if (item.taskId) {
            await this.loadProjects(); // 关联任务状态变化 → 刷新进度
          }
        } catch (error) {
          item.done = !next;
          if (event && event.target) {
            event.target.checked = !next;
          }
          this.fail(error);
        } finally {
          this.reportBusy = false;
        }
      },
      async linkItemProject(item, event) {
        const projectId = event && event.target ? Number(event.target.value) : 0;
        if (!projectId) {
          return;
        }
        this.reportBusy = true;
        try {
          const updated = await api("PATCH", "/api/reports/items/" + item.id, {
            projectId: projectId,
          });
          if (updated) {
            Object.assign(item, updated);
          }
          await this.loadProjects(); // 新建了真实任务 → 刷新进度
          this.toast("已关联项目任务", "ok");
        } catch (error) {
          if (event && event.target) {
            event.target.value = item.projectId || "";
          }
          this.fail(error);
        } finally {
          this.reportBusy = false;
        }
      },
      async removeItem(item) {
        this.reportBusy = true;
        try {
          await api("DELETE", "/api/reports/items/" + item.id);
          this.items = this.items.filter((x) => x.id !== item.id);
        } catch (error) {
          this.fail(error);
        } finally {
          this.reportBusy = false;
        }
      },

      /* ---------------------------------------------------------------- 项目 */
      async loadProjects() {
        try {
          this.projects = (await api("GET", "/api/projects")) || [];
        } catch (error) {
          this.fail(error);
        }
      },
      async openProject(id) {
        this.tab = "projects";
        try {
          this.projectDetail = await api("GET", "/api/projects/" + id);
        } catch (error) {
          this.fail(error);
        }
        this.loadMembers();
      },
      async loadMembers() {
        try {
          this.members = (await api("GET", "/api/members")) || [];
        } catch (error) {
          this.fail(error);
        }
      },
      closeProject() {
        this.projectDetail = null;
        this.loadProjects();
      },
      async deleteProject() {
        const detail = this.projectDetail;
        if (!detail) {
          return;
        }
        if (
          !window.confirm(
            "删除项目《" +
              detail.project.name +
              "》？删除后不再显示，里程碑与任务会一并隐藏（数据保留，可通过接口恢复）。"
          )
        ) {
          return;
        }
        try {
          await api("DELETE", "/api/projects/" + detail.project.id);
          this.closeProject();
          this.toast("项目已删除", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async createProject() {
        const form = this.newProject || {};
        if (!(form.name || "").trim() || !(form.projectKey || "").trim()) {
          this.toast("项目名称和标识不能为空", "error");
          return;
        }
        try {
          await api("POST", "/api/projects", {
            name: form.name.trim(),
            projectKey: form.projectKey.trim(),
            status: form.status || "ACTIVE",
            startDate: form.startDate || null,
            endDate: form.endDate || null,
            description: form.description || "",
          });
          this.newProject = {
            name: "",
            projectKey: "",
            status: "ACTIVE",
            startDate: "",
            endDate: "",
            description: "",
          };
          this.showNewProject = false;
          await this.loadProjects();
          this.toast("项目已创建", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async changeTaskStatus(task, status) {
        try {
          await api("PATCH", "/api/tasks/" + task.id + "/status", { status: status });
          await this.openProject(this.projectDetail.project.id);
          await this.loadProjects();
        } catch (error) {
          this.fail(error);
        }
      },
      async createTask() {
        const form = this.newTask;
        if (!(form.title || "").trim()) {
          this.toast("任务标题不能为空", "error");
          return;
        }
        const id = this.projectDetail.project.id;
        try {
          await api("POST", "/api/projects/" + id + "/tasks", {
            title: form.title.trim(),
            milestoneId: form.milestoneId === "" ? null : Number(form.milestoneId),
            assigneeId: form.assigneeId === "" ? null : Number(form.assigneeId),
            status: form.status || "todo",
            priority: Number(form.priority) || 2,
            startDate: form.startDate || null,
            dueDate: form.dueDate || null,
          });
          this.newTask = {
            title: "",
            priority: 2,
            status: "todo",
            startDate: "",
            dueDate: "",
            milestoneId: "",
            assigneeId: "",
          };
          this.showNewTask = false;
          await this.openProject(id);
          await this.loadProjects();
          this.toast("任务已创建", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async createMilestone() {
        const form = this.newMilestone;
        if (!(form.name || "").trim()) {
          this.toast("里程碑名称不能为空", "error");
          return;
        }
        const id = this.projectDetail.project.id;
        try {
          await api("POST", "/api/projects/" + id + "/milestones", {
            name: form.name.trim(),
            dueDate: form.dueDate || null,
            status: null,
          });
          this.newMilestone = { name: "", dueDate: "" };
          this.showNewMilestone = false;
          await this.openProject(id);
          await this.loadProjects();
          this.toast("里程碑已创建", "ok");
        } catch (error) {
          this.fail(error);
        }
      },

      /* ---------------------------------------------------------------- 成员 */
      async loadUsers() {
        try {
          this.users = (await api("GET", "/api/users")) || [];
        } catch (error) {
          this.fail(error);
        }
      },
      async createUser() {
        const form = this.newUser;
        if (!(form.login || "").trim() || !(form.name || "").trim() || !form.password) {
          this.toast("登录名、姓名、初始密码都不能为空", "error");
          return;
        }
        try {
          await api("POST", "/api/users", {
            login: form.login.trim(),
            name: form.name.trim(),
            password: form.password,
            role: form.role || "MEMBER",
          });
          this.newUser = { login: "", name: "", password: "", role: "MEMBER" };
          this.showNewUser = false;
          await this.loadUsers();
          this.toast("成员已创建", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async updateUser(user, patch) {
        try {
          const updated = await api("PUT", "/api/users/" + user.id, {
            name: patch.name != null ? patch.name : user.name,
            role: patch.role != null ? patch.role : user.role,
            active: patch.active != null ? patch.active : user.active,
          });
          Object.assign(user, updated);
          this.toast("成员信息已更新", "ok");
        } catch (error) {
          this.fail(error);
          await this.loadUsers();
        }
      },
      async resetPassword(user) {
        const password = window.prompt("为 " + user.name + " 设置新密码：");
        if (!password) {
          return;
        }
        try {
          await api("POST", "/api/users/" + user.id + "/password", { newPassword: password });
          this.toast("密码已重置", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async deleteUser(user) {
        if (!window.confirm("删除成员 " + user.name + "？其历史内容会保留。")) {
          return;
        }
        try {
          await api("DELETE", "/api/users/" + user.id);
          await this.loadUsers();
          this.toast("成员已删除", "ok");
        } catch (error) {
          this.fail(error);
        }
      },
      async runBackup() {
        this.backupBusy = true;
        try {
          this.lastBackup = await api("POST", "/api/admin/backup");
          this.toast("备份已完成", "ok");
        } catch (error) {
          this.fail(error);
        } finally {
          this.backupBusy = false;
        }
      },

      /* ---------------------------------------------------------------- 展示辅助 */
      taskStatusLabel(status) {
        return TASK_STATUS[status] || status;
      },
      projectStatusLabel(status) {
        return PROJECT_STATUS[status] || status;
      },
      priorityLabel(priority) {
        return PRIORITY[priority] || priority;
      },
      assigneeName(id) {
        if (!id) {
          return "—";
        }
        const member = this.members.find((m) => m.id === id);
        return member ? member.name : "—";
      },
      taskStatusOptions() {
        return TASK_STATUS_OPTIONS;
      },
      projectStatusOptions() {
        return PROJECT_STATUS_OPTIONS;
      },
    },
  };

  const app = createApp(App);
  app.component("page-tree-node", PageTreeNode);
  app.mount("#app");
})();
