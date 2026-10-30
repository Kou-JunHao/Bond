package com.bond.transfer.config;

import com.bond.transfer.service.PersonalSpaceService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时为存量用户幂等回填个人空间。
 *
 * <p>V2 迁移只加列/索引，不写数据；存量用户（以及历史上注册但创建失败的）在这里补齐。
 * id 由 {@code SnowflakeId.nextId()} 生成，禁止 SQL 硬编码。
 * 回填失败只记录日志、不阻塞启动：{@code /api/files} 首次访问时还会懒创建。</p>
 */
@Component
@RequiredArgsConstructor
public class PersonalSpaceBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PersonalSpaceBackfillRunner.class);

    private final PersonalSpaceService personalSpaceService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            personalSpaceService.backfillMissingPersonalSpaces();
        } catch (Exception e) {
            log.error("Personal space backfill failed; /api/files will lazily create personal spaces instead", e);
        }
    }
}
