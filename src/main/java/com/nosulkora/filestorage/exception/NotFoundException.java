package com.nosulkora.filestorage.exception;

public class NotFoundException extends ApplicationException{
    public NotFoundException(String reason) {
        super(reason);
    }

    @Override
    public int getHttpStatus() {
        return 404;
    }
}
