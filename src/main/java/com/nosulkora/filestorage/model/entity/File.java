package org.nosulkora.filestorage.model.entity;

import org.nosulkora.filestorage.model.enums.FileStatus;

public class File {
    private Integer id;
    private String name;
    private String location; // MinIO S3 URL
    private FileStatus status;

    public File(Integer id, String name, String location, FileStatus status) {
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
