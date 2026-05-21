package com.nosulkora.filestorage.model.dto;

import com.nosulkora.filestorage.model.enums.EventStatus;

import java.time.LocalDateTime;

public class EventDto {
    private Integer id;
    private Integer userId;
    private Integer fileId;
    private EventStatus status;
    private LocalDateTime timestamp;

    public EventDto() {
    }

    public EventDto(Integer id, Integer userId, Integer fileId, EventStatus status, LocalDateTime timestamp) {
        this.id = id;
        this.userId = userId;
        this.fileId = fileId;
        this.status = status;
        this.timestamp = timestamp;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Integer getFileId() {
        return fileId;
    }

    public void setFileId(Integer fileId) {
        this.fileId = fileId;
    }

    public EventStatus getStatus() {
        return status;
    }

    public void setStatus(EventStatus status) {
        this.status = status;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
