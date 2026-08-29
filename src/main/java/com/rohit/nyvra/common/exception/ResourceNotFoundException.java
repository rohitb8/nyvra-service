package com.rohit.nyvra.common.exception;

/** Thrown when a requested entity does not exist or is not visible to the current user. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String type, Object id) {
        return new ResourceNotFoundException("%s %s not found".formatted(type, id));
    }
}
