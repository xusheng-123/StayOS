package com.stayos.auth;

import com.stayos.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class PasswordPolicy {
    private PasswordPolicy() {}
    public static String username(String input) { return input == null ? "" : input.trim().toLowerCase(Locale.ROOT); }
    public static void validateUsername(String value) {
        if (!value.matches("[a-z0-9][a-z0-9_.-]{2,99}")) throw new ApiException(400, "VALIDATION_ERROR", "账号为 3—100 位字母、数字、点、下划线或短横线");
    }
    public static void validatePassword(String value) {
        if (value == null || value.length() < 12 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "VALIDATION_ERROR", "密码至少 12 个字符，UTF-8 编码不超过 72 字节");
    }
}
