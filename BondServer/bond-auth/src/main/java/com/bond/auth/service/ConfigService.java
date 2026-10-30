package com.bond.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.auth.mapper.SystemConfigMapper;
import com.bond.common.entity.SystemConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class ConfigService {

    private final SystemConfigMapper configMapper;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    // Refresh cache every 30 seconds to pick up config changes from bond-transfer
    @Scheduled(fixedRate = 30000)
    public void refreshCache() {
        List<SystemConfig> configs = configMapper.selectList(null);
        cache.clear();
        for (SystemConfig c : configs) {
            cache.put(c.getConfigKey(), c.getConfigValue());
        }
    }

    public String get(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    public String get(String key) {
        return cache.get(key);
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
}
