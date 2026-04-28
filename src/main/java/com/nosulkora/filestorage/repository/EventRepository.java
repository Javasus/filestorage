package com.nosulkora.filestorage.repository;

import com.nosulkora.filestorage.model.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Integer> {
}
