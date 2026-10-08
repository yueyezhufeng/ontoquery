-- ----------------------------------------------------------------------------
-- OntoQuery 本体运维：变更审计日志（幂等，可重复执行）
-- 一次保存 = 一个 changeset（多行）；回退产生新的逆向 changeset（is_revert=1）
-- 规范：表名/字段名小写下划线、is_xxx 布尔列、pk_/idx_ 索引、必备三字段、无外键
-- 作者：月夜烛峰
-- ----------------------------------------------------------------------------

USE ontoquery;

DROP TABLE IF EXISTS ont_change_log;
CREATE TABLE ont_change_log (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    changeset_id  BIGINT UNSIGNED NOT NULL COMMENT '变更集编号，同一次保存的多行共享',
    op_type       VARCHAR(16)     NOT NULL COMMENT 'create / update / delete / revert',
    target_kind   VARCHAR(16)     NOT NULL COMMENT 'instance / synonym / mapping',
    target_code   VARCHAR(128)    NOT NULL COMMENT '实例编码或同义词条（人类可读定位）',
    before_json   TEXT                     DEFAULT NULL COMMENT '操作前行镜像 JSON',
    after_json    TEXT                     DEFAULT NULL COMMENT '操作后行镜像 JSON',
    is_revert     TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否为回退产生的逆向变更集：1/0',
    operator      VARCHAR(64)     NOT NULL DEFAULT 'local' COMMENT '操作员（v1 无鉴权，预留）',
    create_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_change_log PRIMARY KEY (id),
    INDEX idx_ont_change_log_changeset_id (changeset_id),
    INDEX idx_ont_change_log_target_code (target_code)
) ENGINE = InnoDB COMMENT = '本体变更审计日志';
