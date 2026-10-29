package com.nosulkora.filestorage.repository;

import com.nosulkora.filestorage.model.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<Event, Integer> {
    List<Event> findByUserId(Integer userId);
}
