package com.namekart.auction_api.common.exception;

public class ResourceNotFoundException extends RuntimeException {

    private final String resourceName;
    private final Object identifier;

    public ResourceNotFoundException(String resourceName, Object identifier) {
        super(String.format("%s with identifier '%s' was not found", resourceName, identifier));
        this.resourceName = resourceName;
        this.identifier = identifier;
    }

    public String getResourceName() {
        return resourceName;
    }

    public Object getIdentifier() {
        return identifier;
    }
}
