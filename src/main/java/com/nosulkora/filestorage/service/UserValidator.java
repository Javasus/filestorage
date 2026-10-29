package com.nosulkora.filestorage.service;

import com.nosulkora.filestorage.exception.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserValidator {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserValidator.class);

    public void validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            LOGGER.error("Validation failed: Username cannot be empty");
            throw new ValidationException("Username cannot be empty");
        }
    }

    public void validateUserPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            LOGGER.error("Validation failed: Password cannot be empty");
            throw new ValidationException("Password cannot be empty");
        }
    }
}
