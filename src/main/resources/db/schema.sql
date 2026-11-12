-- =====================================================================
-- QA 知识库 · SQLite Schema
-- 与 db/migration/V1__init.sql（MySQL 权威结构）保持列名与枚举值一致
-- 语句分隔符：--> statement-breakpoint
-- 注意：全文检索使用 FTS5 trigram，中文检索策略见 SearchQueryPlanner
-- =====================================================================

CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  login         TEXT    NOT NULL UNIQUE,
  name          TEXT    NOT NULL,
  password_hash TEXT    NOT NULL,
  role          TEXT    NOT NULL DEFAULT 'MEMBER' CHECK (role IN ('ADMIN','MEMBER','VIEWER')),
  active        INTEGER NOT NULL DEFAULT 1,
  created_at    TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS sessions (
  id         TEXT    PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT    NOT NULL,
  last_seen  TEXT    NOT NULL,
  expires_at TEXT    NOT NULL
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS settings (
  k TEXT NOT NULL PRIMARY KEY,
  v TEXT NOT NULL
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS spaces (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL,
  slug        TEXT    NOT NULL UNIQUE,
  icon        TEXT    NOT NULL DEFAULT '',
  description TEXT    NOT NULL DEFAULT '',
  sort        INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS pages (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  space_id   INTEGER NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
  parent_id  INTEGER REFERENCES pages(id) ON DELETE SET NULL,
  title      TEXT    NOT NULL,
  slug       TEXT    NOT NULL,
  content_md TEXT    NOT NULL,
  status     TEXT    NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('DRAFT','PUBLISHED')),
  author_id  INTEGER NOT NULL REFERENCES users(id),
  sort       INTEGER NOT NULL DEFAULT 0,
  created_at TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  deleted_at TEXT
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_pages_space ON pages(space_id, parent_id, sort);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_pages_deleted ON pages(deleted_at);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS page_revisions (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  page_id    INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  title      TEXT    NOT NULL,
  content_md TEXT    NOT NULL,
  editor_id  INTEGER NOT NULL REFERENCES users(id),
  created_at TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_revisions_page ON page_revisions(page_id, created_at);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS tags (
  id   INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS page_tags (
  page_id INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  tag_id  INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
  PRIMARY KEY (page_id, tag_id)
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS attachments (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  page_id     INTEGER NOT NULL REFERENCES pages(id) ON DELETE CASCADE,
  filename    TEXT    NOT NULL,
  stored_name TEXT    NOT NULL,
  size        INTEGER NOT NULL,
  mime        TEXT    NOT NULL,
  uploader_id INTEGER NOT NULL REFERENCES users(id),
  created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_attachments_page ON attachments(page_id);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS projects (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL,
  project_key TEXT    NOT NULL UNIQUE,
  owner_id    INTEGER NOT NULL REFERENCES users(id),
  status      TEXT    NOT NULL DEFAULT 'ACTIVE'
              CHECK (status IN ('PLANNING','ACTIVE','DONE','ARCHIVED')),
  start_date  TEXT,
  end_date    TEXT,
  description TEXT,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  deleted_at  TEXT
);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS milestones (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  name       TEXT    NOT NULL,
  due_date   TEXT,
  status     TEXT    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DONE'))
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_milestones_project ON milestones(project_id);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS tasks (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id   INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  milestone_id INTEGER REFERENCES milestones(id) ON DELETE SET NULL,
  title        TEXT    NOT NULL,
  assignee_id  INTEGER REFERENCES users(id) ON DELETE SET NULL,
  status       TEXT    NOT NULL DEFAULT 'todo'
               CHECK (status IN ('todo','doing','done','blocked')),
  priority     INTEGER NOT NULL DEFAULT 2,
  start_date   TEXT,
  due_date     TEXT,
  progress     INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  created_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_tasks_project ON tasks(project_id, status);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_tasks_assignee ON tasks(assignee_id);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS daily_reports (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  report_date TEXT    NOT NULL,
  done        TEXT    NOT NULL,
  plan        TEXT    NOT NULL,
  blockers    TEXT    NOT NULL,
  status      TEXT    NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','submitted')),
  created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  UNIQUE (user_id, report_date)
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_reports_date ON daily_reports(report_date);
--> statement-breakpoint

CREATE TABLE IF NOT EXISTS daily_report_projects (
  report_id  INTEGER NOT NULL REFERENCES daily_reports(id) ON DELETE CASCADE,
  project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  PRIMARY KEY (report_id, project_id)
);
--> statement-breakpoint

-- 周报清单条目：轻量待办，每条可选关联到项目任务（勾选同步任务状态）
CREATE TABLE IF NOT EXISTS report_items (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  report_id   INTEGER NOT NULL REFERENCES daily_reports(id) ON DELETE CASCADE,
  title       TEXT    NOT NULL,
  done        INTEGER NOT NULL DEFAULT 0,
  task_id     INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
  project_id  INTEGER REFERENCES projects(id) ON DELETE SET NULL,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_report_items_report ON report_items(report_id, sort_order);
--> statement-breakpoint

-- P2 操作审计：谁在何时对什么做了什么（与 MySQL db/migration/V1__init.sql 同结构）。
-- 刻意不设 user_id 外键：成员被删除后审计记录必须保留，登录名快照走查询端 JOIN users。
CREATE TABLE IF NOT EXISTS audit_log (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER,
  action      TEXT    NOT NULL,
  target_type TEXT    NOT NULL,
  target_id   TEXT,
  detail      TEXT    NOT NULL DEFAULT '',
  created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
);
--> statement-breakpoint

CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at);
--> statement-breakpoint

-- =====================================================================
-- 全文检索：FTS5 trigram（外部内容表，指向 pages）
-- 查询少于 3 个字符时 trigram 不会命中，由 SearchQueryPlanner 走 LIKE
-- =====================================================================
CREATE VIRTUAL TABLE IF NOT EXISTS pages_fts USING fts5(
  title,
  content_md,
  content='pages',
  content_rowid='id',
  tokenize='trigram'
);
--> statement-breakpoint

CREATE TRIGGER IF NOT EXISTS pages_ai AFTER INSERT ON pages BEGIN
  INSERT INTO pages_fts(rowid, title, content_md) VALUES (new.id, new.title, new.content_md);
END;
--> statement-breakpoint

CREATE TRIGGER IF NOT EXISTS pages_ad AFTER DELETE ON pages BEGIN
  INSERT INTO pages_fts(pages_fts, rowid, title, content_md)
    VALUES ('delete', old.id, old.title, old.content_md);
END;
--> statement-breakpoint

CREATE TRIGGER IF NOT EXISTS pages_au AFTER UPDATE ON pages BEGIN
  INSERT INTO pages_fts(pages_fts, rowid, title, content_md)
    VALUES ('delete', old.id, old.title, old.content_md);
  INSERT INTO pages_fts(rowid, title, content_md) VALUES (new.id, new.title, new.content_md);
END;
--> statement-breakpoint

-- SQLite 没有 MySQL 的 ON UPDATE CURRENT_TIMESTAMP，用触发器补齐
CREATE TRIGGER IF NOT EXISTS projects_touch AFTER UPDATE ON projects
WHEN new.updated_at = old.updated_at BEGIN
  UPDATE projects SET updated_at = datetime('now','localtime') WHERE id = new.id;
END;
--> statement-breakpoint

CREATE TRIGGER IF NOT EXISTS tasks_touch AFTER UPDATE ON tasks
WHEN new.updated_at = old.updated_at BEGIN
  UPDATE tasks SET updated_at = datetime('now','localtime') WHERE id = new.id;
END;
--> statement-breakpoint

CREATE TRIGGER IF NOT EXISTS reports_touch AFTER UPDATE ON daily_reports
WHEN new.updated_at = old.updated_at BEGIN
  UPDATE daily_reports SET updated_at = datetime('now','localtime') WHERE id = new.id;
END;
--> statement-breakpoint

CREATE TRIGGER IF NOT EXISTS report_items_touch AFTER UPDATE ON report_items
WHEN new.updated_at = old.updated_at BEGIN
  UPDATE report_items SET updated_at = datetime('now','localtime') WHERE id = new.id;
END;
