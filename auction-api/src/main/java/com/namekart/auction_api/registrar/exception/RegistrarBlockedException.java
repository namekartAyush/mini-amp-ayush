package com.namekart.auction_api.registrar.exception;

/**
 * Thrown when the external registrar returns a blocked response
 * (such as an anti-bot challenge or rate limit error) even though HTTP status is 200 OK.
 */
public class RegistrarBlockedException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public RegistrarBlockedException(String message) {
        super(message);
        this.statusCode = 200;
        this.errorCode = "BLOCKED";
    }

    public RegistrarBlockedException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
