package com.scione.scm.bill.interfaces;

import com.scione.common.response.ApiResponse;
import com.scione.scm.bill.common.BusinessException;
import com.scione.scm.bill.common.ResultCode;
import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import com.scione.scm.bill.application.DeliveryFollowUpImportService;
import com.scione.scm.bill.application.ProcurementOperationLogRecorder;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 将校验、领域和基础设施异常转换为统一 API 响应，同时保留正确的 HTTP 状态。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final DeliveryFollowUpImportService followUpImportService;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        return response(exception.getResultCode(), exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException exception) {
        return parameterError(bindingErrors(exception.getBindingResult()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraintViolation(ConstraintViolationException exception) {
        return parameterError(null);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequestParameter(Exception exception) {
        log.info("Request parameter rejected: {}", exception.getMessage());
        return parameterError(null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<? extends ApiResponse<?>> uploadTooLarge(
            MaxUploadSizeExceededException exception, HttpServletRequest request) {
        String operator = request.getHeader(ProcurementOperationLogRecorder.OPERATOR_HEADER);
        if ("/api/v1/delivery-alerts/imports".equals(request.getServletPath())
                && operator != null && !operator.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(followUpImportService.rejectFile(
                    "超限上传文件", "上传文件超过大小限制，请拆分后导入", operator)));
        }
        return response(ResultCode.IMPORT_FILE_TOO_LARGE);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformedJson(HttpMessageNotReadableException exception) {
        log.info("Request body cannot be read", exception);
        return response(ResultCode.JSON_PARSE_ERROR);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return response(ResultCode.REQUEST_METHOD_ERROR);
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiResponse<Void>> duplicateKey(DuplicateKeyException exception) {
        log.info("Database unique constraint violated", exception);
        return response(ResultCode.DATABASE_DUPLICATE_KEY);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> database(DataAccessException exception) {
        log.error("Database operation failed", exception);
        return response(ResultCode.DATABASE_ERROR);
    }

    /**
     * 浏览器直接访问后端根路径、favicon.ico 等不存在的静态资源时，Spring 会抛这个异常。
     * 它不是业务错误，落到 Exception 兜底里会打整段堆栈、还返回 500，日志被刷得看不清真正的问题。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> staticResourceMissing(NoResourceFoundException exception) {
        log.info("静态资源不存在：path={}", exception.getResourcePath());
        return ResponseEntity.notFound().build();
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
        return response(ResultCode.PARAM_ERROR, message);
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
        return response(resultCode, resultCode.getMessage());
    }

    private ResponseEntity<ApiResponse<Void>> response(ResultCode resultCode, String message) {
        return ResponseEntity.status(ResultCodeHttpStatusMapper.statusOf(resultCode))
                .body(ApiResponse.fail(resultCode.getCode(), message));
    }
}
