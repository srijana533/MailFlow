package com.mailflow.common.exception;

import org.springframework.http.HttpStatus;

public class ValidationException extends MailFlowException {

    public ValidationException(String message) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }
}
