package com.nebula.common.exception;

import lombok.Getter;

@Getter
public class NebulaException extends RuntimeException {
    private final String errorCode;
    private final int httpStatus;

    public NebulaException(String message, String errorCode, int httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }

    public NebulaException(String message, String errorCode, int httpStatus, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
