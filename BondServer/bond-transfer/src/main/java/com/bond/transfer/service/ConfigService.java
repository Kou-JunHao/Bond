package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.entity.SystemConfig;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.SystemConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final SystemConfigMapper configMapper;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    @Scheduled(fixedRate = 30000)
    public void refreshCache() {
        List<SystemConfig> configs = configMapper.selectList(null);
        cache.clear();
        for (SystemConfig c : configs) {
            cache.put(c.getConfigKey(), c.getConfigValue());
        }
    }

    public String get(String key) {
        return cache.get(key);
    }

    public String get(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    public long getLong(String key, long defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return defaultValue; }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v);
    }

    /**
     * task-106：按 system_config.config_type 校验并规范化后写入。
     *
     * <p>非法值 → 400（message 带键名与期望类型），不会落库、不会污染缓存。</p>
     */
    public void update(String key, String value, Long userId) {
        SystemConfig config = configMapper.selectById(key);
        if (config == null) {
            throw new BusinessException(404, "配置项不存在: " + key);
        }
        String normalized = ConfigValueNormalizer.normalize(key, config.getConfigType(), value);
        config.setConfigValue(normalized);
        config.setUpdatedBy(userId);
        configMapper.updateById(config);
        cache.put(key, normalized);
    }

    /**
     * 写入配置（存在则更新、不存在则插入，task-63 邮件设置需要能补全新键），并即时刷新本地缓存。
     * 与 {@link #update} 的区别：不要求行已存在；用于后台保存「可新增」的配置组。
     */
    public void set(String key, String value, Long userId) {
        SystemConfig config = configMapper.selectById(key);
        if (config == null) {
            config = new SystemConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            config.setConfigType("string");
            config.setUpdatedBy(userId);
            configMapper.insert(config);
        } else {
            config.setConfigValue(value);
            config.setUpdatedBy(userId);
            configMapper.updateById(config);
        }
        cache.put(key, value);
    }

    /**
     * task-106：批量写入——先全部校验，再统一落库，事务内要么全成功要么全失败。
     *
     * <p>先校验的关键点：避免「前几条已写入/缓存已更新，后面才失败」的半成品状态；
     * 数据库层再加 {@code @Transactional} 兜底（写库异常时整体回滚）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdate(Map<String, String> updates, Long userId) {
        if (updates == null || updates.isEmpty()) {
            throw new BusinessException(400, "批量更新内容不能为空");
        }
        // 第一遍：读行 + 校验规范化（任何一条失败都不会产生写操作）
        Map<String, SystemConfig> configs = new LinkedHashMap<>();
        Map<String, String> normalizedValues = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String key = entry.getKey();
            SystemConfig config = configMapper.selectById(key);
            if (config == null) {
                throw new BusinessException(404, "配置项不存在: " + key);
            }
            configs.put(key, config);
            normalizedValues.put(key,
                    ConfigValueNormalizer.normalize(key, config.getConfigType(), entry.getValue()));
        }
        // 第二遍：全部合法后统一写入并刷新缓存
        for (Map.Entry<String, SystemConfig> entry : configs.entrySet()) {
            SystemConfig config = entry.getValue();
            config.setConfigValue(normalizedValues.get(entry.getKey()));
            config.setUpdatedBy(userId);
            configMapper.updateById(config);
        }
        cache.putAll(normalizedValues);
    }

    public List<SystemConfig> listAll() {
        return configMapper.selectList(null);
    }

    public void resetToDefaults() {
        configMapper.delete(null);
        insertDefaults();
        refreshCache();
    }

    private void insertDefaults() {
        String[][] defaults = {
            {"transfer.max_file_size", "5368709120", "long", "单文件最大体积(字节), 默认5GB"},
            {"transfer.chunk_size", "5242880", "int", "默认分片大小(字节), 默认5MB"},
            {"transfer.large_file_threshold", "1073741824", "long", "大文件分片阈值(字节), 默认1GB"},
            {"transfer.large_chunk_size", "10485760", "int", "大文件分片大小(字节), 默认10MB"},
            {"transfer.max_chunk_size", "67108864", "long", "单分片最大体积(字节), 默认64MB（防超大分片）"},
            {"transfer.max_chunks", "200000", "long", "单任务最大分片数（防止分片元数据爆炸）"},
            {"entitlement.me_cache_seconds", "15", "int", "权益接口 /api/entitlements/me 本地缓存秒数(0=关闭)"},
            {"entitlement.cache_seconds", "60", "int", "档位解析器(EntitlementResolver)本地缓存秒数(0=关闭，默认60s)"},
            {"share.public_base_url", "https://bond.example.com", "string", "分享链接对外域名(用于拼接 /s/{token} URL)"},
            {"share.code_max_attempts", "5", "int", "分享提取码连续错误次数上限(达到后锁定)"},
            {"share.code_lock_minutes", "10", "int", "分享提取码锁定分钟数"},
            {"share.rate_limit_per_minute", "30", "int", "分享匿名接口每 IP+token 每分钟请求上限(0=关闭)"},
            {"thumbnail.rate_limit_per_minute", "600", "int", "缩略图接口每用户每分钟请求上限(0=关闭，超限 429+Retry-After)"},
            {"thumbnail.cache_days", "30", "int", "缩略图缓存保留天数(每日 GC 兜底清理，默认30天)"},
            {"chat.rate_limit_per_minute", "30", "int", "聊天发送消息每用户每分钟上限(0=关闭，超限 429+Retry-After)"},
            {"chat.message_max_chars", "4000", "int", "聊天单条正文最大字符数(默认4000，超限 400)"},
            {"announcement.max_content_chars", "10000", "int", "公告正文最大字符数(默认10000，超限 400)"},
            {"trash.retention_days", "7", "int", "回收站保留天数全局兜底(档位 limits 缺失时, 0=不限期; 档位值优先)"},
            {"trash.purge_batch", "200", "int", "回收站过期清理单轮上限(条/次)"},
            {"trash.purge_max_rounds", "20", "int", "回收站过期清理单次任务最多轮数(1~100, 默认20)"},
            {"trash.purge_cron", "0 10 3 * * *", "string", "回收站过期清理 cron(6 段 Spring 语法，默认每天 03:10)"},
            {"upload.chunk_size", "8388608", "int", "网盘分片上传默认分片大小(字节, 5MB~64MB, 默认8MB)"},
            {"upload.max_chunks", "10000", "long", "网盘分片上传单文件最大分片数(默认10000, 即64MB分片下640GB)"},
            {"upload.session_ttl_hours", "24", "int", "网盘上传会话有效期(小时, 1~168, 默认24)"},
            {"upload.cleanup_batch", "200", "int", "网盘过期上传会话每小时清理单轮上限(条/次, 0=关闭)"},
            {"webdav.enabled", "false", "boolean", "WebDAV 挂载开关(/dav/**，默认 false；先在服务器验证再打开)"},
            {"transfer.upload_concurrency", "4", "int", "分片上传并发数"},
            {"transfer.task_expire_days", "7", "int", "任务过期天数"},
            {"transfer.free_upload_limit", "2097152", "long", "免费用户上行限速(字节/秒)"},
            {"transfer.free_download_limit", "5242880", "long", "免费用户下行限速(字节/秒)"},
            {"transfer.paid_upload_limit", "10485760", "long", "付费用户上行限速(字节/秒)"},
            {"transfer.paid_download_limit", "20971520", "long", "付费用户下行限速(字节/秒)"},
            {"transfer.require_friend", "true", "boolean", "跨网传输是否需要好友关系"},
            {"workspace.max_size", "5368709120", "long", "单工作区最大空间(字节)"},
            {"workspace.max_create", "3", "int", "每用户可创建工作区数"},
            {"workspace.max_join", "10", "int", "每用户可加入工作区数"},
            {"workspace.max_members", "20", "int", "单工作区最大成员数"},
            {"workspace.max_file_size", "2147483648", "long", "工作区单文件最大体积(字节)"},
            {"workspace.file_retention_days", "90", "int", "文件保留天数"},
            {"workspace.invite_expire_days", "7", "int", "邀请链接有效期(天)"},
            {"invite.public_base_url", "https://bond-api.sg.skkk.uno", "string", "邀请落地页对外域名（拼接 /i/{token} URL）"},
            {"user.allow_register", "true", "boolean", "是否允许新用户注册"},
            {"friend.request_expire_days", "7", "int", "好友请求有效期(天)"},
            {"friend.max_friends", "500", "int", "最大好友数"},
            {"nat.enabled", "true", "boolean", "是否启用NAT穿透"},
            {"nat.stun_server", "", "string", "STUN服务器地址"},
            {"nat.punch_timeout_ms", "5000", "int", "NAT打洞超时(毫秒)"},
            {"relay.enabled", "true", "boolean", "是否启用Relay中继"},
            {"relay.chunk_size", "65536", "int", "Relay子块大小(字节)"},
            {"relay.timeout_seconds", "30", "int", "Relay超时(秒)"},
            {"relay.fallback_to_minio", "true", "boolean", "Relay降级到MinIO"},
            {"system.name", "Bond", "string", "系统名称"},
            {"system.public_url", "", "string", "公网地址"},
            {"system.jwt_access_expire_hours", "24", "int", "JWT Access Token有效期(小时)"},
            {"system.jwt_refresh_expire_days", "7", "int", "JWT Refresh Token有效期(天)"},
            {"system.cleanup_cron", "0 0 3 * * ?", "string", "数据清理Cron表达式"},
            {"document.max_content_bytes", "10485760", "long", "单篇文档正文硬上限(字节, 默认10MB；与档位 max_file_size 取小)"},
            {"document.version_keep", "50", "int", "每文档保留最近版本数(默认50)"},
            {"document.version_total_bytes", "20971520", "long", "每文档版本历史总字节上限(字节, 默认20MB，超出从最老删)"},
            {"document.version_cleanup_batch", "200", "int", "版本清理每小时单轮上限(篇/次)"},
            // task-63：发信设置（DB 为唯一主来源；敏感值由后台接口 AES-GCM 加密写入 enc:v1:）
            {"email.provider", "smtp", "string", "发信通道: smtp|resend|bondmail|off（默认 smtp；SMTP 未配置时回退可用 resend）"},
            {"email.from.address", "", "string", "发件地址（留空时回退 SMTP 用户名）"},
            {"email.from.name", "Bond", "string", "发件人显示名"},
            {"email.smtp.host", "", "string", "SMTP 主机"},
            {"email.smtp.port", "587", "int", "SMTP 端口（465=隐式TLS；587=STARTTLS）"},
            {"email.smtp.username", "", "string", "SMTP 用户名"},
            {"email.smtp.password", "", "string", "SMTP 口令（AES-GCM 密文 enc:v1:）"},
            {"email.smtp.auth", "true", "boolean", "SMTP 是否认证"},
            {"email.smtp.starttls", "false", "boolean", "SMTP STARTTLS（与 ssl 互斥）"},
            {"email.smtp.ssl", "false", "boolean", "SMTP 隐式 TLS（465）"},
            {"email.smtp.timeout_seconds", "5", "int", "SMTP 超时秒数（默认5）"},
            {"email.resend.api_key", "", "string", "Resend API Key（AES-GCM 密文）"},
            {"email.resend.endpoint", "https://api.resend.com", "string", "Resend endpoint"},
            {"email.bondmail.endpoint", "", "string", "BondMail endpoint（可选兼容通道）"},
            {"office.preview.enabled", "true", "boolean", "Office 文档转 PDF 预览开关"},
            {"office.preview.endpoint", "http://bond-gotenberg:3000", "string", "Gotenberg 转换服务地址"},
            {"office.preview.timeout-seconds", "90", "int", "单次转换超时秒数"},
            {"office.preview.max-source-bytes", "104857600", "long", "允许转换的源文件上限（字节）"},
            {"office.preview.concurrency", "2", "int", "同时转换并发上限"},
            {"office.preview.cache_version", "3", "string", "转换器/字体配置版本（进缓存键；升级 Gotenberg 镜像或字体别名后升版本以绕过旧 PDF）"},
            {"office.preview.single-page-sheets", "true", "boolean", "表格类文档（xlsx/xls/ods/csv）整表缩放到一页，避免宽表截列；false 则按表格自身打印区域导出"},
            // task-74：存储卫生 GC（孤儿对象默认只报告；预览缓存 TTL 默认开启）
            {"gc.orphan.enabled", "true", "boolean", "孤儿对象扫描开关（只报告）"},
            {"gc.orphan.delete", "false", "boolean", "孤儿对象是否真的删除（默认 false，只报告）"},
            {"gc.orphan.min_age_hours", "24", "int", "对象最后修改超过该小时数才允许删除"},
            {"gc.orphan.max_delete_per_run", "500", "int", "单轮最多删除的孤儿对象数（上限 2000）"},
            {"gc.preview.enabled", "true", "boolean", "预览缓存 TTL 清理开关"},
            {"preview.cache_ttl_days", "30", "int", "thumbnails/previews 缓存保留天数"},
            // 重启窗口：备份文件与暂存目录保留策略（由部署脚本读取）
            {"backup.retention_days", "30", "int", "PG 备份保留天数"},
            {"backup.keep_last", "3", "int", "至少保留最近几份 PG 备份"},
            {"staging.retention_days", "7", "int", "重启暂存 jar 保留天数"},
            // task-80：邮件投递日志保留天数（清理 job 每天 03:40 执行）
            {"email.delivery_log.retention_days", "30", "int", "邮件投递日志保留天数（默认 30，过期由定时任务清理）"},
            // task-81：软件分发包大小上限（默认 2GB）
            {"software.max_release_bytes", "2147483648", "long", "软件安装包单文件上限（字节，默认 2GB）"},
            // task-157：过期数据统一清理（每天一次；开关关闭即暂停，回滚=关开关）
            {"ops.cleanup.enabled", "true", "boolean", "过期数据清理总开关（false=所有清理任务暂停）"},
            {"ops.cleanup.cron", "0 20 3 * * *", "string", "过期数据清理 cron（6 段 Spring 语法；按容器时区，生产为 UTC → 北京 11:20）"},
            {"ops.cleanup.batch", "200", "int", "单批最多处理行数（1~500，默认 200）"},
            {"ops.cleanup.max_batches", "50", "int", "单次任务最多批数（1~100，默认 50；与 batch 相乘为单次上限）"},
            {"ops.trash_gc.enabled", "true", "boolean", "回收站过期项物理清理开关"},
            {"ops.share_expiry.enabled", "true", "boolean", "分享链接过期标记开关（status 0→2，不删行）"},
            {"ops.upload_session_gc.enabled", "true", "boolean", "僵死上传会话清理开关（未完成且超 24h）"},
            {"ops.audit_log_gc.enabled", "true", "boolean", "审计日志保留清理开关"},
            {"ops.audit_log_retention_days", "90", "int", "管理操作/登录审计日志保留天数（默认 90）"},
        };
        for (String[] d : defaults) {
            SystemConfig c = new SystemConfig();
            c.setConfigKey(d[0]);
            c.setConfigValue(d[1]);
            c.setConfigType(d[2]);
            c.setDescription(d[3]);
            configMapper.insert(c);
        }
    }
}
