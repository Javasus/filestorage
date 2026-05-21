package com.nosulkora.filestorage.mapper;

import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.User;

public class UserMapper {
    public static UserDto toDto(User user) {
        if(user == null) {
            return null;
        }
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setStatus(user.getStatus());
        dto.setRole(user.getRole());
        return dto;
    }

    public static User toEntity(UserDto dto) {
        if(dto == null) {
            return null;
        }
        User user = new User();
        user.setId(dto.getId());
        user.setUsername(dto.getUsername());
        user.setStatus(dto.getStatus());
        user.setRole(dto.getRole());
        return user;
    }
}
