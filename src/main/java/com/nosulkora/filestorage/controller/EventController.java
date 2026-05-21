package com.nosulkora.filestorage.controller;

import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.repository.EventRepository;
import com.nosulkora.filestorage.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/events")
@Tag(name = "Event Controller", description = "CRUD operations for event")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get event by ID")
    public Mono<EventDto> getById(@PathVariable Integer id) {
        return eventService.findById(id);
    }

    @GetMapping
    @Operation(summary = "Get all events")
    public Flux<EventDto> getAll() {
        return eventService.findAll();
    }
}
