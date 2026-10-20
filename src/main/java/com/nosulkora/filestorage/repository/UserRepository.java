package com.nosulkora.filestorage.repository;

import com.nosulkora.filestorage.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Integer> {
    Optional<User> findByUsername(String username);

    @Query("SELECT u.tokenVersion as tokenVersion, u.status as status FROM User u WHERE u.username = :username")
    Optional<UserSecurityInfo> findSecurityInfoByUserName(@Param("username") String username);
}
