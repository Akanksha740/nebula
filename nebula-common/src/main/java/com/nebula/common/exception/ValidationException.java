package com.nebula.common.exception;

import lombok.Getter;

import java.util.Map;

@Getter
public class ValidationException extends NebulaException {
    private final Map<String, String> fieldErrors;

    public ValidationException(String message) {
        super(message, "VALIDATION_ERROR", 400);
        this.fieldErrors = null;
    }

    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(message, "VALIDATION_ERROR", 400);
        this.fieldErrors = fieldErrors;
    }
}
