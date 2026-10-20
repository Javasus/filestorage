package com.nosulkora.filestorage.model.entity;

import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import jakarta.persistence.*;

import java.util.List;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private UserStatus status;

    @Column(nullable = false)
    private UserRole role; // ADMIN, MODERATOR, USER

    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion = 1;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Event> events;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<File> files;

    public User() {
    }

    public User(
            Integer id,
            String username,
            String passwordHash,
            UserStatus status,
            UserRole role,
            List<Event> events,
            List<File> files
    ) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.status = status;
        this.role = role;
        this.events = events;
        this.files = files;
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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public List<Event> getEvents() {
        return events;
    }

    public void setEvents(List<Event> events) {
        this.events = events;
    }

    public List<File> getFiles() {
        return files;
    }

    public void setFiles(List<File> files) {
        this.files = files;
    }

    public Integer getTokenVersion() {
        return tokenVersion;
    }

    public void setTokenVersion(Integer tokenVersion) {
        this.tokenVersion = tokenVersion;
    }
}
