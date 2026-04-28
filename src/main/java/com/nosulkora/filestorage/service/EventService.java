package com.nosulkora.filestorage.service;

import com.nosulkora.filestorage.model.dto.EventDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface EventService {
    Mono<EventDto> create(EventDto eventDto);
    Mono<EventDto> update(EventDto eventDto);
    Mono<Void> delete(Integer id);
    Mono<EventDto> findById(Integer id);
    Flux<EventDto> findAll();
}
