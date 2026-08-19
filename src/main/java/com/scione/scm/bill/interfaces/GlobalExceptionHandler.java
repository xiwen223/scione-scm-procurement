package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 将校验、领域和基础设施异常转换为统一 API 响应，同时保留正确的 HTTP 状态。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        return response(exception.getResultCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception) {
        return parameterError(bindingErrors(exception.getBindingResult()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraintViolation(ConstraintViolationException exception) {
        return parameterError(null);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequestParameter(Exception exception) {
        log.warn("Request parameter rejected: {}", exception.getMessage());
        return parameterError(null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> uploadTooLarge(MaxUploadSizeExceededException exception) {
        return response(ResultCode.IMPORT_FILE_TOO_LARGE);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformedJson(HttpMessageNotReadableException exception) {
        log.warn("Request body cannot be read", exception);
        return response(ResultCode.JSON_PARSE_ERROR);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(ResultCode.REQUEST_METHOD_ERROR);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> duplicateKey(DuplicateKeyException exception) {
        log.warn("Database unique constraint violated", exception);
        return response(ResultCode.DATABASE_DUPLICATE_KEY);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> database(DataAccessException exception) {
        log.error("Database operation failed", exception);
        return response(ResultCode.DATABASE_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        log.error("Unhandled application error", exception);
        return response(ResultCode.SYSTEM_ERROR);
    }

    private ResponseEntity<ApiResponse<Void>> parameterError(String detail) {
        String message = detail == null || detail.isBlank()
                ? ResultCode.PARAM_ERROR.getMessage()
                : ResultCode.PARAM_ERROR.getMessage() + "：" + detail;
        return ResponseEntity.status(ResultCodeHttpStatusMapper.statusOf(ResultCode.PARAM_ERROR))
                .body(ApiResponse.fail(ResultCode.PARAM_ERROR.getCode(), message));
    }

    private String bindingErrors(BindingResult bindingResult) {
        return bindingResult.getFieldErrors().stream()
                .map(error -> error.getField() + "：" + error.getDefaultMessage())
                .distinct()
                .sorted()
                .reduce((first, second) -> first + "；" + second)
                .orElse(null);
    }

    private ResponseEntity<ApiResponse<Void>> response(ResultCode resultCode) {
        return ResponseEntity.status(ResultCodeHttpStatusMapper.statusOf(resultCode))
                .body(ApiResponse.fail(resultCode.getCode(), resultCode.getMessage()));
    }
}
