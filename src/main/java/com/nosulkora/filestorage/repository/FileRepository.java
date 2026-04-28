package com.nosulkora.filestorage.repository;

import com.nosulkora.filestorage.model.entity.File;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileRepository extends JpaRepository<File, Integer> {
}
