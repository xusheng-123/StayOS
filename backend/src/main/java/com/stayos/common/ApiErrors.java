package com.stayos.common;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;

@RestControllerAdvice
public class ApiErrors {
    private final com.stayos.audit.AuditService audit;
    public ApiErrors(com.stayos.audit.AuditService audit) { this.audit = audit; }
    private void recordFailure() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        Long actor = auth != null && auth.getPrincipal() instanceof com.stayos.auth.SessionIdentity identity ? identity.userId() : null;
        audit.failure(actor, "REQUEST_FAILED", "API", null);
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> business(ApiException e) {
        if (!e.code().equals("LOGIN_FAILED")) recordFailure();
        return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
        org.springframework.validation.BindException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> validation(Exception ignored) { recordFailure(); return ResponseEntity.badRequest().body(ApiResponse.error("VALIDATION_ERROR", "请检查输入内容")); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(DataIntegrityViolationException error) {
        recordFailure();
        String detail = error.getMostSpecificCause().getMessage();
        String code="DATA_CONFLICT", message="数据冲突，请刷新后重试";
        if (detail != null && detail.contains("uq_room_number")) { code="ROOM_NUMBER_EXISTS"; message="房号已存在，请使用其他房号"; }
        else if (detail != null && detail.contains("uq_room_type_code")) { code="ROOM_TYPE_CODE_EXISTS"; message="房型编码已存在，请使用其他编码"; }
        else if (detail != null && detail.contains("uq_room_active_segment")) { code="ROOM_NOT_AVAILABLE"; message="房间已被其他操作占用，请刷新后重试"; }
        else if (detail != null && detail.contains("uq_room_open_cleaning_task")) { code="TASK_ALREADY_EXISTS"; message="该房间已有未完成的清洁任务，请查看原任务"; }
        return ResponseEntity.status(409).body(ApiResponse.error(code,message));
    }
    // Let Spring Security's filter handler audit access denial and return the common JSON format.
    @ExceptionHandler(AccessDeniedException.class)
    void denied(AccessDeniedException e) { throw e; }
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
        org.springframework.web.servlet.NoHandlerFoundException.class})
    ResponseEntity<?> missing(Exception ignored) {
        return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "请求的接口不存在"));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(Exception ignored) { return ResponseEntity.status(405).body(ApiResponse.error("METHOD_NOT_ALLOWED","此接口不支持该操作")); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ignored) { recordFailure(); return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR", "操作失败，请稍后重试")); }
}
