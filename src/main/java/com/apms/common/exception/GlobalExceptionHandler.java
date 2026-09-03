package com.apms.common.exception;

import com.apms.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BusinessConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessConflict(BusinessConflictException ex) {
        log.warn("Business conflict error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(BusinessValidationException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleBusinessValidation(BusinessValidationException ex) {
        log.warn("Business validation error: [{}] {}", ex.getErrorCode(), ex.getMessage());

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("success", false);
        body.put("message", ex.getMessage());
        body.put("timestamp", java.time.LocalDateTime.now());

        if (ex.getErrorCode() != null) {
            body.put("errorCode", ex.getErrorCode());
        }
        if (ex.getDetails() != null && !ex.getDetails().isEmpty()) {
            body.put("details", ex.getDetails());
        }

        // Use 422 for document company validation errors, 400 for others
        HttpStatus status = ex.getErrorCode() != null && ex.getErrorCode().startsWith("DOCUMENT_COMPANY")
                ? HttpStatus.UNPROCESSABLE_ENTITY
                : HttpStatus.BAD_REQUEST;

        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining(", "));
        log.warn("Validation error: {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("Validation failed: " + message));
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(Exception ex) {
        log.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error("Access Denied"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("Malformed JSON payload: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error("Malformed JSON payload"));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.warn("Service unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<java.util.Map<String, Object>> handleOptimisticLockingFailure(org.springframework.dao.OptimisticLockingFailureException ex) {
        log.warn("Optimistic locking failure: {}", ex.getMessage());
        java.util.Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", false);
        response.put("message", "Document was modified by another transaction. Please reload and try again.");
        // Try to extract document version or revision if we want, but usually it's in the exception message
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> handleDuplicateKeyException(org.springframework.dao.DuplicateKeyException ex) {
        log.warn("Duplicate key error: {}", ex.getMessage());
        String message = "Thông tin đã tồn tại trên hệ thống.";
        if (ex.getMessage() != null) {
            if (ex.getMessage().contains("taxCode")) {
                message = "Mã số thuế này đã tồn tại trên hệ thống.";
            } else if (ex.getMessage().contains("registrationNumber")) {
                message = "Mã số doanh nghiệp này đã tồn tại trên hệ thống.";
            }
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneralException(Exception ex) {
        log.error("Unexpected error occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }
}
