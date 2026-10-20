package com.stayos.common;

public record ApiResponse<T>(boolean success, String code, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, null, "操作成功", data); }
    public static ApiResponse<Void> error(String code, String message) { return new ApiResponse<>(false, code, message, null); }
}
