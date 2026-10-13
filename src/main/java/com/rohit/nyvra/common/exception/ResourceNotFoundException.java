package com.rohit.nyvra.common.exception;

/** Thrown when a requested entity does not exist or is not visible to the current user. */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message client-safe description of what was not found
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a not-found exception with a standard message.
     *
     * @param type entity name, for example {@code Goal}
     * @param id identifier that was looked up
     * @return an exception with message {@code "<type> <id> not found"}
     */
    public static ResourceNotFoundException of(String type, Object id) {
        return new ResourceNotFoundException("%s %s not found".formatted(type, id));
    }
}
