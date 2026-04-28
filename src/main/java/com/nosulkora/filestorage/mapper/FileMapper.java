package com.nosulkora.filestorage.mapper;

import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.entity.File;

public class FileMapper {
    public static FileDto toDto(File file) {
        if (file == null) {
            return null;
        }
        FileDto dto = new FileDto();
        dto.setId(file.getId());
        dto.setName(file.getName());
        dto.setLocation(file.getLocation());
        dto.setStatus(file.getStatus());
        return dto;
    }

    public static File toEntity(FileDto dto) {
        if (dto == null) {
            return null;
        }
        File file = new File();
        file.setId(dto.getId());
        file.setName(dto.getName());
        file.setLocation(dto.getLocation());
        file.setStatus(dto.getStatus());
        return file;
    }
}
