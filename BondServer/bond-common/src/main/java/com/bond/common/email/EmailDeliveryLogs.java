package com.bond.common.email;

import com.bond.common.entity.EmailDeliveryLog;

import java.util.List;

/**
 * 投递记录 → 实体映射（task-80），auth / transfer 两个落库实现共用。
 *
 * <p>变量名列表序列化为 JSON 数组字符串；变量值不会进入记录对象。
 * task-129：{@code variable_keys} 落「实际提供的变量名 ∪ 缺失变量名」，这样后台能看到缺了哪些。</p>
 */
public final class EmailDeliveryLogs {

    private EmailDeliveryLogs() {
    }

    public static EmailDeliveryLog toEntity(EmailDeliveryRecorder.EmailDeliveryRecord record) {
        EmailDeliveryLog log = new EmailDeliveryLog();
        log.setUserId(record.userId());
        log.setToMasked(record.toMasked());
        log.setPurpose(record.purpose());
        log.setTemplateCode(record.templateCode());
        log.setSubject(record.subject() == null ? "" : record.subject());
        log.setStatus(record.status());
        log.setFailureReason(record.failureReason());
        log.setVariableKeys(jsonArray(mergeVariableNames(record.variableKeys(), record.missingVariables())));
        log.setPlaceholdersLeft(record.placeholdersLeft());
        log.setProvider(record.provider());
        log.setDurationMs(record.durationMs());
        log.setAttempts(record.attempts() <= 0 ? 1 : record.attempts());
        log.setResendPayload(record.resendPayload());
        return log;
    }

    /**
     * 实际提供 + 缺失的变量名并集（升序去重）。
     *
     * <p>task-129：投递日志没有新增列，按任务要求复用 {@code variable_keys} 记录
     * 「模板涉及的全部变量名（含缺失）」，避免加迁移。</p>
     */
    static List<String> mergeVariableNames(List<String> provided, List<String> missing) {
        java.util.TreeSet<String> union = new java.util.TreeSet<>();
        if (provided != null) {
            union.addAll(provided);
        }
        if (missing != null) {
            union.addAll(missing);
        }
        return List.copyOf(union);
    }

    /** 变量名列表 → JSON 数组字符串（如 ["code","username"]）。 */
    public static String jsonArray(List<String> keys) {        if (keys == null || keys.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"').append(escape(keys.get(i))).append('"');
        }
        return out.append(']').toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
