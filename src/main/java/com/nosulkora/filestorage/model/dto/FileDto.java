package com.nosulkora.filestorage.model.dto;

import com.nosulkora.filestorage.model.enums.FileStatus;

public class FileDto {
    private Integer id;
    private String name;
    private String location;
    private FileStatus status;

    public FileDto() {
    }

    public FileDto(Integer id, String name, String location, FileStatus status) {
        this.id = id;
        this.name = name;
        this.location = location;
        this.status = status;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public FileStatus getStatus() {
        return status;
    }

    public void setStatus(FileStatus status) {
        this.status = status;
    }
}
