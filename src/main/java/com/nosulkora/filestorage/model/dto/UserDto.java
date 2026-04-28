package com.nosulkora.filestorage.model.dto;

import com.nosulkora.filestorage.model.enums.UserStatus;

public class UserDto {
    private Integer id;
    private String username;
    private UserStatus status;

    public UserDto() {
    }

    public UserDto(Integer id, String username, UserStatus status) {
        this.id = id;
        this.username = username;
        this.status = status;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }
}
