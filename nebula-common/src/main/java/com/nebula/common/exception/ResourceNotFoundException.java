package com.nebula.common.exception;

public class ResourceNotFoundException extends NebulaException {
    
    public ResourceNotFoundException(String resourceType, String identifier) {
        super(
            String.format("%s not found: %s", resourceType, identifier),
            "RESOURCE_NOT_FOUND",
            404
        );
    }

    public ResourceNotFoundException(String message) {
        super(message, "RESOURCE_NOT_FOUND", 404);
    }
}
