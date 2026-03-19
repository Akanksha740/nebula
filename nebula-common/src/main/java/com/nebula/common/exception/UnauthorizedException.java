package com.nebula.common.exception;

public class UnauthorizedException extends NebulaException {
    
    public UnauthorizedException(String message) {
        super(message, "UNAUTHORIZED", 401);
    }

    public UnauthorizedException() {
        super("Invalid or missing authentication", "UNAUTHORIZED", 401);
    }
}
