package com.bond.transfer.webdav;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bond.common.dto.WebDavPasswordCreatedDTO;
import com.bond.common.dto.WebDavPasswordDTO;
import com.bond.common.entity.WebDavPassword;
import com.bond.common.exception.BusinessException;
import com.bond.common.utils.SnowflakeId;
import com.bond.transfer.mapper.WebDavPasswordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * WebDAV 应用专用密码（task-48）。
 *
 * <p>生成时返回一次性明文（{@code Bond-XXXX-XXXX-XXXX-XXXX} 形式，24 位；base32 字母表已去掉
 * 易混的 0/O/1/I，便于用户手抄进 Windows 凭据管理器），服务端只存 bcrypt；吊销是软吊销，
 * 并立即清掉 {@link WebDavAuthService} 的进程内认证缓存，保证吊销后下一次请求就 401。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebDavPasswordService {

    /** 同一用户最多保留的未吊销专用密码数（先写死；后续可跟档位挂钩）。 */
    static final int MAX_ACTIVE_PER_USER = 10;
    static final int NAME_MAX_LENGTH = 64;

    /** 32 字符 base32 字母表：A-Z 去掉 I/O，再加数字 2-9（去掉了易混的 0 和 1）。 */
    static final String BASE32_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    /** 10 字节 = 80 bit → 16 个 5-bit 符号 → 4 组 × 4 字符，总长 24。 */
    private static final int RANDOM_BYTES = 10;
    private static final int GROUP_COUNT = 4;
    private static final int GROUP_LENGTH = 4;
    static final int PLAINTEXT_LENGTH = "Bond".length() + GROUP_COUNT * (GROUP_LENGTH + 1);

    private final WebDavPasswordMapper mapper;
    private final WebDavAuthService authService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    /** 列表：只返回未吊销的密码；永不回传 hash。 */
    public List<WebDavPasswordDTO> list(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<WebDavPassword>()
                        .eq(WebDavPassword::getUserId, userId)
                        .isNull(WebDavPassword::getRevokedAt)
                        .orderByDesc(WebDavPassword::getCreatedAt))
                .stream()
                .map(WebDavPasswordService::toDto)
                .toList();
    }

    /** 生成专用密码：返回一次性明文；bcrypt 落库；同用户上限 10。 */
    public WebDavPasswordCreatedDTO create(Long userId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new BusinessException(400, "名称不能为空");
        }
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(400, "名称过长（最多 " + NAME_MAX_LENGTH + " 字符）");
        }
        long active = mapper.selectCount(new LambdaQueryWrapper<WebDavPassword>()
                .eq(WebDavPassword::getUserId, userId)
                .isNull(WebDavPassword::getRevokedAt));
        if (active >= MAX_ACTIVE_PER_USER) {
            throw new BusinessException(400,
                    "专用密码数量已达上限（" + MAX_ACTIVE_PER_USER + "），请先吊销不再使用的密码");
        }

        String plaintext = generatePlaintext();
        WebDavPassword row = new WebDavPassword();
        row.setId(SnowflakeId.nextId());
        row.setUserId(userId);
        row.setName(trimmed);
        row.setPasswordHash(passwordEncoder.encode(plaintext));
        mapper.insert(row);

        WebDavPasswordCreatedDTO dto = new WebDavPasswordCreatedDTO();
        dto.setId(row.getId());
        dto.setName(row.getName());
        dto.setPassword(plaintext);
        log.info("WebDAV 专用密码已生成: userId={}, passwordId={}", userId, row.getId());
        return dto;
    }

    /** 吊销：软吊销（置 revoked_at），并立即清掉该用户的认证缓存（吊销后立刻 401）。 */
    public void revoke(Long userId, Long passwordId) {
        WebDavPassword row = mapper.selectById(passwordId);
        if (row == null || !row.getUserId().equals(userId)) {
            throw new BusinessException(404, "password not found");
        }
        if (row.getRevokedAt() == null) {
            row.setRevokedAt(OffsetDateTime.now());
            mapper.updateById(row);
            log.info("WebDAV 专用密码已吊销: userId={}, passwordId={}", userId, passwordId);
        }
        authService.invalidateUser(userId);
    }

    /** 10 字节随机数 → 16 个 base32 符号，按 4 组拼成 {@code Bond-XXXX-XXXX-XXXX-XXXX}。 */
    private String generatePlaintext() {
        byte[] bytes = new byte[RANDOM_BYTES];
        secureRandom.nextBytes(bytes);
        StringBuilder symbols = new StringBuilder(GROUP_COUNT * GROUP_LENGTH);
        int buffer = 0;
        int bitsInBuffer = 0;
        int index = 0;
        for (int i = 0; i < GROUP_COUNT * GROUP_LENGTH; i++) {
            while (bitsInBuffer < 5) {
                buffer = (buffer << 8) | (bytes[index++] & 0xff);
                bitsInBuffer += 8;
            }
            bitsInBuffer -= 5;
            symbols.append(BASE32_ALPHABET.charAt((buffer >> bitsInBuffer) & 0x1f));
        }
        StringBuilder plaintext = new StringBuilder(PLAINTEXT_LENGTH);
        plaintext.append("Bond");
        for (int group = 0; group < GROUP_COUNT; group++) {
            plaintext.append('-').append(symbols, group * GROUP_LENGTH, (group + 1) * GROUP_LENGTH);
        }
        return plaintext.toString();
    }

    static WebDavPasswordDTO toDto(WebDavPassword row) {
        WebDavPasswordDTO dto = new WebDavPasswordDTO();
        dto.setId(row.getId());
        dto.setName(row.getName());
        dto.setCreatedAt(epochMillis(row.getCreatedAt()));
        dto.setLastUsedAt(epochMillis(row.getLastUsedAt()));
        dto.setRevoked(row.getRevokedAt() != null);
        return dto;
    }

    private static Long epochMillis(OffsetDateTime time) {
        return time == null ? null : time.toInstant().toEpochMilli();
    }
}
