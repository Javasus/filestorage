package org.nosulkora.filestorage.model.entity;

import org.nosulkora.filestorage.model.enums.EventStatus;
import java.time.LocalDateTime;

public class Event {
    private Integer id;
    private User user;
    private File file;
    private EventStatus status;
    private LocalDateTime timeStamp;

    public Event(Integer id, User user, File file, EventStatus status, LocalDateTime timeStamp) {
        this.id = id;
        this.user = user;
        this.file = file;
        this.status = status;
        this.timeStamp = timeStamp;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public EventStatus getStatus() {
        return status;
    }

    public void setStatus(EventStatus status) {
        this.status = status;
    }

    public LocalDateTime getTimeStamp() {
        return timeStamp;
    }

    public void setTimeStamp(LocalDateTime timeStamp) {
        this.timeStamp = timeStamp;
    }
}
