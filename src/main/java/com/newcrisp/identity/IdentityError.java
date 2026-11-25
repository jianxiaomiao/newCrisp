package com.newcrisp.identity;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

public final class IdentityError extends RuntimeException {
    public final HttpStatus status;
    public final String code;

    public IdentityError(HttpStatus status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public static IdentityError unauthorized() { return new IdentityError(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED"); }
    static IdentityError origin() { return new IdentityError(HttpStatus.FORBIDDEN, "ORIGIN_REJECTED"); }
    static IdentityError invalidGrant() { return new IdentityError(HttpStatus.UNAUTHORIZED, "INVALID_LAUNCH_GRANT"); }
    static IdentityError unavailable() { return new IdentityError(HttpStatus.SERVICE_UNAVAILABLE, "NEXUS_UNAVAILABLE"); }
    public static IdentityError notFound() { return new IdentityError(HttpStatus.NOT_FOUND, "NOT_FOUND"); }
}

@RestControllerAdvice
class IdentityErrorHandler {
    @ExceptionHandler(IdentityError.class)
    ResponseEntity<ErrorBody> identity(IdentityError error) {
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore())
                .body(new ErrorBody(error.code, UUID.randomUUID().toString()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestValueException.class})
    ResponseEntity<ErrorBody> badRequest() {
        return identity(new IdentityError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
    }

    record ErrorBody(String code, String traceId) {}
}
