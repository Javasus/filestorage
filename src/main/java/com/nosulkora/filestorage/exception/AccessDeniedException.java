package com.nosulkora.filestorage.exception;

public class AccessDeniedException extends ApplicationException {

    public AccessDeniedException(String reason) {
        super(reason);
    }

    @Override
    public int getHttpStatus() {
        return 403;
    }
}
