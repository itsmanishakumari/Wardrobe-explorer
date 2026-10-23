package com.wardrobe;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static com.wardrobe.ApiModels.ErrorView;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorView> application(ApiException error) {
        return response(error.status(), error.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorView> validation(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .sorted().findFirst().orElse("Invalid request.");
        return response(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorView> oversized() {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "Images must be no larger than 10 MB.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, ServletRequestBindingException.class, MultipartException.class})
    ResponseEntity<ErrorView> badRequest() {
        return response(HttpStatus.BAD_REQUEST, "Invalid request. Check required fields, types, and allowed values.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorView> missing() {
        return response(HttpStatus.NOT_FOUND, "Resource not found.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorView> mediaType() {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported request content type.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorView> method() {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method not supported.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorView> unexpected(Exception error) {
        LOG.error("Unexpected backend failure", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed. Please try again.");
    }

    private ResponseEntity<ErrorView> response(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorView(message));
    }
}