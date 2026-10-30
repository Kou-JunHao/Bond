package com.bond.common.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * task-79：6 个用途「发送方实际提供的变量」必须覆盖「模板声明的变量（去掉网关默认注入）」。
 *
 * <p>声明变量以 <b>V16 迁移里的生产 seed</b> 为准（DB 行优先于内置模板，DB 声明的变量才是线上真实的渲染依据）。
 * 本测试直接从 classpath 读取迁移文件解析，避免手抄声明与实际 seed 漂移。</p>
 *
 * <p>发送方变量集合按各发送方代码当前传入值维护：</p>
 * <ul>
 *   <li>VerificationCodeService（register_code / password_reset / change_email）：
 *       code、email、expiresMinutes、username</li>
 *   <li>AuthService（welcome）：username、email</li>
 *   <li>AdminUserEmailService（admin_reset_password）：code、username</li>
 *   <li>AdminUserEmailService（admin_operation_notice）：action、username、email、reason</li>
 * </ul>
 */
class EmailPurposeVariableConsistencyTest {

    private static final String V16 = "/db/migration/V16__email_settings_templates.sql";

    private static final Map<String, Map<String, String>> PROVIDED = new LinkedHashMap<>();

    static {
        PROVIDED.put(EmailPurpose.REGISTER_CODE, Map.of(
                "code", "123456", "email", "alice@example.com",
                "expiresMinutes", "10", "username", "alice"));
        PROVIDED.put(EmailPurpose.PASSWORD_RESET, Map.of(
                "code", "123456", "email", "alice@example.com",
                "expiresMinutes", "10", "username", "alice"));
        PROVIDED.put(EmailPurpose.CHANGE_EMAIL, Map.of(
                "code", "123456", "email", "new@example.com",
                "expiresMinutes", "10", "username", "alice"));
        PROVIDED.put(EmailPurpose.WELCOME, Map.of(
                "username", "alice", "email", "alice@example.com"));
        PROVIDED.put(EmailPurpose.ADMIN_RESET_PASSWORD, Map.of(
                "code", "Tmp#1234", "username", "alice"));
        PROVIDED.put(EmailPurpose.ADMIN_OPERATION_NOTICE, Map.of(
                "action", "账号已被禁用", "username", "alice",
                "email", "alice@example.com", "reason", "违规操作"));
    }

    static Stream<EmailPurpose.Purpose> purposes() {
        return EmailPurpose.all().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("purposes")
    @DisplayName("发送方变量覆盖 V16 seed 声明的变量（网关默认变量除外）")
    void senderProvidesAllDeclaredVariables(EmailPurpose.Purpose purpose) throws IOException {
        List<String> declared = declaredVariablesInV16(purpose.builtinTemplateCode());
        assertFalse(declared.isEmpty(),
                "V16 未找到模板 " + purpose.builtinTemplateCode() + " 的 variables 声明");

        Map<String, String> provided = PROVIDED.get(purpose.code());
        assertNotNull(provided, "缺少用途的发送方变量定义: " + purpose.code());

        List<String> missing = EmailTemplateValidator.missingVariables(declared, provided, null);
        assertTrue(missing.isEmpty(),
                "用途 " + purpose.code() + " 的发送方漏传变量: " + missing + "（模板声明 " + declared + "）");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("purposes")
    @DisplayName("用途 requiredVariables 已包含在发送方变量里")
    void senderProvidesRequiredVariables(EmailPurpose.Purpose purpose) {
        Map<String, String> provided = PROVIDED.get(purpose.code());
        for (String required : purpose.requiredVariables()) {
            assertTrue(provided.containsKey(required),
                    "用途 " + purpose.code() + " 缺必需变量 " + required);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("purposes")
    @DisplayName("welcome 的 DB 声明必须包含 email（本次缺陷的回归锚点）")
    void welcomeDeclaresEmail(EmailPurpose.Purpose purpose) throws IOException {
        if (!EmailPurpose.WELCOME.equals(purpose.code())) {
            return;
        }
        assertTrue(declaredVariablesInV16(purpose.builtinTemplateCode()).contains("email"),
                "welcome 模板声明应包含 email");
    }

    /** 从 V16 seed 中提取指定模板行尾的 variables JSON 数组（行内唯一一段 '{@code ["..."]}'::jsonb）。 */
    private static List<String> declaredVariablesInV16(String templateCode) throws IOException {
        String sql = readResource(V16);
        // 行格式：(\n 'verification_code', '名称', ...  '["code",...]'::jsonb, TRUE, TRUE\n)
        int row = sql.indexOf("'" + templateCode + "', '");
        assertTrue(row > 0, "V16 未找到模板行: " + templateCode);
        int jsonbQuote = sql.indexOf("'::jsonb", row);
        assertTrue(jsonbQuote > row, "V16 模板行缺少 ::jsonb: " + templateCode);
        int openQuote = sql.lastIndexOf('\'', jsonbQuote - 1);
        assertTrue(openQuote > row, "V16 模板行缺少 variables 数组字面量: " + templateCode);
        return EmailTemplateValidator.parseVariables(sql.substring(openQuote + 1, jsonbQuote));
    }

    private static String readResource(String path) throws IOException {
        try (InputStream in = EmailPurposeVariableConsistencyTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath 缺少 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
