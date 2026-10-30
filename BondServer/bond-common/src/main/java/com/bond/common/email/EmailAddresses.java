package com.bond.common.email;

import com.bond.common.exception.BusinessException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 邮箱规范（task-57）：trim + 小写；长度 ≤254；基础格式校验。
 * 放 bond-common，bond-auth 与 bond-transfer 共用同一口径，避免两边规则漂移。
 */
public final class EmailAddresses {

    private static final Pattern PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}$");
    private static final int MAX_LENGTH = 254;

    private EmailAddresses() {
    }

    /**
     * @throws BusinessException 400：为空 / 超长 / 格式错误
     */
    public static String normalize(String email) {
        if (email == null) {
            throw new BusinessException(400, "邮箱不能为空");
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH || !PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(400, "邮箱格式不正确");
        }
        return normalized;
    }
}
