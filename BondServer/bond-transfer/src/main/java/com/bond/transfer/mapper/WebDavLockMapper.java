package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.WebDavLock;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * WebDAV 锁表 Mapper。
 *
 * <p>必须放在 {@code com.bond.transfer.mapper} 下：{@code BondTransferApplication} 用的是
 * {@code @MapperScan("com.bond.transfer.mapper")}，一旦存在 @MapperScan，MyBatis 就不再扫描其它包里的
 * {@code @Mapper} 接口。放到 webdav 包会导致 Spring 启动时找不到该 bean（生产表现为 transfer 重启失败、
 * 文件/WebDAV 接口全部 503），单测却全绿——因为单测用的是内存实现。</p>
 */
@Mapper
public interface WebDavLockMapper extends BaseMapper<WebDavLock> {

    @Select("SELECT * FROM webdav_locks WHERE timeout_at > #{now} ORDER BY created_at ASC")
    List<WebDavLock> selectActive(@Param("now") OffsetDateTime now);

    @Select("SELECT * FROM webdav_locks WHERE token = #{token}")
    WebDavLock selectByToken(@Param("token") String token);

    @Update("UPDATE webdav_locks SET timeout_at = #{timeoutAt} WHERE token = #{token}")
    int updateTimeout(@Param("token") String token, @Param("timeoutAt") OffsetDateTime timeoutAt);

    @Delete("DELETE FROM webdav_locks WHERE token = #{token}")
    int deleteByToken(@Param("token") String token);

    @Delete("DELETE FROM webdav_locks WHERE timeout_at <= #{now}")
    int deleteExpired(@Param("now") OffsetDateTime now);
}
