package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.mapper.EventMapper;
import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.repository.EventRepository;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.service.EventService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public class EventServiceImpl implements EventService {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final FileRepository fileRepository;

    public EventServiceImpl(
            EventRepository eventRepository,
            UserRepository userRepository,
            FileRepository fileRepository
    ) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.fileRepository = fileRepository;
    }

    @Override
    public Mono<EventDto> create(EventDto eventDto) {
        return ReactiveWrapper.mono(() -> {
            // Получаем юзер и файл из БД.
            User user = userRepository.findById(eventDto.getUserId())
                    .orElseThrow(() -> new RuntimeException("User not found by ID: " + eventDto.getUserId()));

            File file = fileRepository.findById(eventDto.getFileId())
                    .orElseThrow(() -> new RuntimeException("File not found by ID: " + eventDto.getFileId()));

            //Создаем Event
            Event event = new Event();
            event.setUser(user);
            event.setFile(file);
            event.setStatus(eventDto.getStatus() != null ? eventDto.getStatus() : EventStatus.CREATED);
            event.setTimeStamp(LocalDateTime.now());
            return eventRepository.save(event);
        }).map(EventMapper::toDto);
    }

    @Override
    public Mono<EventDto> update(EventDto eventDto) {
        return ReactiveWrapper.mono(() -> {
           Event existingEvent = eventRepository.findById(eventDto.getId())
                   .orElseThrow(() -> new RuntimeException("Event not found by ID: " + eventDto.getId()));

           if (eventDto.getStatus() != null) {
               existingEvent.setStatus(EventStatus.UPDATED);
           }
           existingEvent.setTimeStamp(LocalDateTime.now());
           return eventRepository.save(existingEvent);
        }).map(EventMapper::toDto);
    }

    @Override
    public Mono<Void> delete(Integer id) {
        return ReactiveWrapper.voidOperation(() -> eventRepository.deleteById(id));
    }

    @Override
    public Mono<EventDto> findById(Integer id) {
        return ReactiveWrapper.monoOptional(() -> eventRepository.findById(id))
                .map(EventMapper::toDto);
    }

    @Override
    public Flux<EventDto> findAll() {
        return ReactiveWrapper.flux(eventRepository::findAll)
                .map(EventMapper::toDto);
    }
}
