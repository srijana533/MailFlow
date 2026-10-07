package com.mailflow.common.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends MailFlowException {

    public ResourceNotFoundException(String resource, Object id) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND",
              String.format("%s not found with id: %s", resource, id));
    }

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }
}
