package com.nosulkora.filestorage.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;

public class UserDto {
    private Integer id;
    private String username;
    private UserStatus status;
    private UserRole role;
    // Защита от отправки пароля в Json.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    public UserDto() {
    }

    public UserDto(Integer id, String username, UserStatus status, UserRole role, String password) {
        this.id = id;
        this.username = username;
        this.status = status;
        this.role = role;
        this.password = password;
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

    public UserRole getRole() { return role; }

    public void setRole(UserRole role) { this.role = role; }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
