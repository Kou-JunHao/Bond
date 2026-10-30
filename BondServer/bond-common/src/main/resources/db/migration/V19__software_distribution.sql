-- task-81：项目内软件分发库（安装包 / 更新包）。
-- 纯追加：只新建两张表，不改动 V1–V18 的任何表/列。
-- 访问：客户端 /api/software/**（登录用户）；管理端 /api/admin/software/**（X-Is-Admin 守卫）。
-- 文件本体进 RustFS（键 software/{code}/{version}/{fileName}），与网盘配额/流量完全隔离。

CREATE TABLE IF NOT EXISTS software_packages (
    id          BIGINT       PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    platform    VARCHAR(16)  NOT NULL DEFAULT 'any',
    created_by  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_software_packages_code UNIQUE (code),
    CONSTRAINT fk_software_packages_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS software_releases (
    id             BIGINT       PRIMARY KEY,
    package_id     BIGINT       NOT NULL,
    version        VARCHAR(64)  NOT NULL,
    channel        VARCHAR(16)  NOT NULL DEFAULT 'stable',
    file_name      VARCHAR(255) NOT NULL,
    file_size      BIGINT       NOT NULL,
    sha256         CHAR(64)     NOT NULL,
    minio_path     VARCHAR(1024) NOT NULL,
    release_notes  TEXT,
    published      BOOLEAN      NOT NULL DEFAULT FALSE,
    download_count BIGINT       NOT NULL DEFAULT 0,
    created_by     BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    CONSTRAINT fk_software_releases_package FOREIGN KEY (package_id) REFERENCES software_packages (id) ON DELETE CASCADE,
    CONSTRAINT fk_software_releases_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uk_software_releases_version UNIQUE (package_id, version, channel)
);

CREATE INDEX IF NOT EXISTS idx_software_releases_package_channel
    ON software_releases (package_id, channel, published, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_software_releases_published
    ON software_releases (published, created_at DESC);

-- packages.updated_at 复用 V1 的 set_updated_at()
DROP TRIGGER IF EXISTS trg_software_packages_set_updated_at ON software_packages;
CREATE TRIGGER trg_software_packages_set_updated_at
    BEFORE UPDATE ON software_packages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
