package com.nosulkora.filestorage.factory;

import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import com.nosulkora.filestorage.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UserTestDataFactory {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserTestDataFactory(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public Builder builder() {
        return new Builder();
    }

    public class Builder {

        private Integer id = 1;
        private String username = "user_" + UUID.randomUUID().toString().substring(0, 8);
        private String rawPassword = "password123";
        private UserStatus userStatus = UserStatus.ACTIVE;
        private UserRole userRole = UserRole.USER;
        private Integer tokenVersion = 1;

        public Builder id(Integer id) {
            this.id = id;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String rawPassword) {
            this.rawPassword = rawPassword;
            return this;
        }

        public Builder status(UserStatus userStatus) {
            this.userStatus = userStatus;
            return this;
        }

        public Builder role(UserRole userRole) {
            this.userRole = userRole;
            return this;
        }

        public Builder tokenVersion(Integer tokenVersion) {
            this.tokenVersion = tokenVersion;
            return this;
        }

        public User build() {
            User user = new User();
            user.setId(id);
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            user.setStatus(userStatus);
            user.setRole(userRole);
            user.setTokenVersion(tokenVersion);
            return userRepository.saveAndFlush(user);
        }
    }
}
