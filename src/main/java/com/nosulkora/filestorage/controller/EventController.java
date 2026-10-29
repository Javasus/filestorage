package com.nosulkora.filestorage.controller;

import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create event manually (ADMIN/MODERATOR only)")
    public Mono<EventDto> create(@RequestBody EventDto eventDto) {
        return eventService.create(eventDto);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update event status (ADMIN/MODERATOR only)")
    public Mono<EventDto> update(@PathVariable Integer id, @RequestBody EventDto eventDto) {
        eventDto.setId(id);
        return eventService.update(eventDto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete event by ID (ADMIN/MODERATOR only)")
    public Mono<Void> delete(@PathVariable Integer id) {
        return eventService.delete(id);
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
