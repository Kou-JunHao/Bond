package com.bond.transfer.webdav;

import com.bond.common.entity.WebDavLock;
import com.bond.transfer.mapper.WebDavLockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/** 生产实现：MyBatis-Plus + PostgreSQL（{@code webdav_locks} 表）。 */
@Component
@RequiredArgsConstructor
public class WebDavLockStoreImpl implements WebDavLockStore {

    private final WebDavLockMapper mapper;

    @Override
    public List<WebDavLock> findActive(OffsetDateTime now) {
        return mapper.selectActive(now);
    }

    @Override
    public WebDavLock findByToken(String token) {
        return mapper.selectByToken(token);
    }

    @Override
    public void insert(WebDavLock lock) {
        mapper.insert(lock);
    }

    @Override
    public void updateTimeout(String token, OffsetDateTime timeoutAt) {
        mapper.updateTimeout(token, timeoutAt);
    }

    @Override
    public void deleteByToken(String token) {
        mapper.deleteByToken(token);
    }

    @Override
    public int deleteExpired(OffsetDateTime now) {
        return mapper.deleteExpired(now);
    }
}
