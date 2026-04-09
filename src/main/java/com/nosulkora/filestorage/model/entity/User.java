package org.nosulkora.filestorage.model.entity;

import org.nosulkora.filestorage.model.enums.UserStatus;

import java.util.List;

public class User {
    private Integer id;
    private String username;
    private UserStatus status;
    private List<Event> events;

    public User(Integer id, String username, UserStatus status, List<Event> events) {
        this.id = id;
        this.username = username;
        this.status = status;
        this.events = events;
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

    public List<Event> getEvents() {
        return events;
    }

    public void setEvents(List<Event> events) {
        this.events = events;
    }
}
