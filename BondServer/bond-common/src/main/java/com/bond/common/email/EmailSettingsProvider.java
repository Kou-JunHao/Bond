package com.bond.common.email;

/**
 * 发信配置读取 SPI（task-63）。
 *
 * <p>实现放在 bond-auth / bond-transfer 各自模块（用本服务的 system_configs 访问方式），
 * bond-common 只提供值对象与发送实现，避免公共模块反向依赖某个服务的 Mapper。</p>
 */
public interface EmailSettingsProvider {
    EmailSettings load();
}
