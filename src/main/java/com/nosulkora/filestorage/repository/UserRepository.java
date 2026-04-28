package com.nosulkora.filestorage.repository;

import com.nosulkora.filestorage.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Integer> {
}
