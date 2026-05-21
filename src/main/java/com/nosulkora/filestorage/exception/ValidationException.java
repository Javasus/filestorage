package com.nosulkora.filestorage.exception;

public class ValidationException extends ApplicationException {

    public ValidationException(String reason) {
        super(reason);
    }

    @Override
    public int getHttpStatus() {
        return 400;
    }
}
