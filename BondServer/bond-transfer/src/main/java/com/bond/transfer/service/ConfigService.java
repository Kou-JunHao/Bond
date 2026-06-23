package com.bond.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.entity.SystemConfig;
import com.bond.common.exception.BusinessException;
import com.bond.transfer.mapper.SystemConfigMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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

    public void update(String key, String value, Long userId) {
        SystemConfig config = configMapper.selectById(key);
        if (config == null) {
            throw new BusinessException(404, "配置项不存在: " + key);
        }
        config.setConfigValue(value);
        config.setUpdatedBy(userId);
        configMapper.updateById(config);
        cache.put(key, value);
    }

    public void batchUpdate(Map<String, String> updates, Long userId) {
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            update(entry.getKey(), entry.getValue(), userId);
        }
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
