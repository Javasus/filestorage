package com.nosulkora.filestorage.repository;

import com.nosulkora.filestorage.model.enums.UserStatus;

public interface UserSecurityInfo {
    Integer getTokenVersion();
    UserStatus getStatus();
}
