package com.bond.transfer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bond.common.entity.SoftwarePackage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}
