package com.nosulkora.filestorage.security;

import com.nosulkora.filestorage.model.enums.UserRole;

import java.security.Principal;

public class UserPrincipal implements Principal {
    private final Integer id;
    private final String username;
    private final UserRole role;

    public UserPrincipal(Integer id, String username, UserRole role) {
        this.id = id;
        this.username = username;
        this.role = role;
    }

    @Override
    public String getName() {
        return username;
    }

    public Integer getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }
}
