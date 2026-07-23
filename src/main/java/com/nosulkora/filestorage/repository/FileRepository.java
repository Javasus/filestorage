package com.nosulkora.filestorage.repository;

import com.nosulkora.filestorage.model.entity.File;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FileRepository extends JpaRepository<File, Integer> {
    List<File> findByUserId(Integer userId);
    void deleteAll();
}
