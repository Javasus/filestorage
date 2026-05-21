package com.nosulkora.filestorage.model.entity;

import com.nosulkora.filestorage.model.enums.FileStatus;
import jakarta.persistence.*;

@Entity
@Table(name = "files")
public class File {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String location; // MinIO S3 URL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileStatus status;

    public File() {
    }

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
