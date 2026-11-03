package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.SoftwarePackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 软件分发包（task-81）。
 */
@Mapper
public interface SoftwarePackageMapper extends BaseMapper<SoftwarePackage> {

    @Select("SELECT * FROM software_packages WHERE code = #{code}")
    SoftwarePackage selectByCode(@Param("code") String code);

    /** 客户端可见：至少存在一个已发布版本的包。 */
    @Select("SELECT p.* FROM software_packages p "
            + "WHERE EXISTS (SELECT 1 FROM software_releases r WHERE r.package_id = p.id AND r.published = TRUE) "
            + "ORDER BY p.name ASC, p.code ASC")
    List<SoftwarePackage> selectPublished();

    /** 管理端：全部包，按名称排序。 */
    @Select("SELECT * FROM software_packages ORDER BY name ASC, code ASC")
    List<SoftwarePackage> selectAllOrdered();

    /**
     * 刷新包的 updated_at（版本上传 / 发布 / 下架 / 删除后调用）。
     *
     * <p>管理端列表的「更新时间」取的就是这一列；以前这些操作只动 {@code software_releases}，
     * 包行没有任何 UPDATE，于是「更新时间」永远停在建包/改名那一刻。
     * V19 的 {@code trg_software_packages_set_updated_at} 触发器会在任何 UPDATE 时把该列置为 now()，
     * 这里仍显式写一次：语义自解释，也不依赖触发器是否被误删。</p>
     */
    @Update("UPDATE software_packages SET updated_at = now() WHERE id = #{id}")
    int touchUpdatedAt(@Param("id") Long id);
}
