package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.mapper.EventMapper;
import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.repository.EventRepository;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.service.EventService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class EventServiceImpl implements EventService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventServiceImpl.class);

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
                    .orElseThrow(() -> new NotFoundException("User not found by ID: " + eventDto.getUserId()));

            File file = fileRepository.findById(eventDto.getFileId())
                    .orElseThrow(() -> new NotFoundException("File not found by ID: " + eventDto.getFileId()));

            //Создаем Event
            Event event = new Event();
            event.setUser(user);
            event.setFile(file);
            event.setStatus(eventDto.getStatus() != null ? eventDto.getStatus() : EventStatus.CREATED);
            event.setTimeStamp(LocalDateTime.now());

            Event savedEvent = eventRepository.save(event);
            LOGGER.info("Event saved with id: {}", savedEvent.getId());
            return savedEvent;
        }).map(EventMapper::toDto);
    }

    @Override
    public Mono<EventDto> update(EventDto eventDto) {
        return SecurityUtils.getCurrentUserName()
                .flatMap(currentUsername -> canAccessEvent(currentUsername)
                        .flatMap(canAccess -> {
                            if (!canAccess) {
                                LOGGER.warn("User {} attempted to update event", currentUsername);
                                return Mono.error(new ValidationException("Only ADMIN can update events"));
                            }
                            return ReactiveWrapper.mono(() -> {
                                Event existingEvent = eventRepository.findById(eventDto.getId())
                                        .orElseThrow(() -> new NotFoundException(
                                                "Event not found by ID: " + eventDto.getId()));

                                if (eventDto.getStatus() != null) {
                                    existingEvent.setStatus(EventStatus.UPDATED);
                                }
                                existingEvent.setTimeStamp(LocalDateTime.now());
                                Event updatedEvent = eventRepository.save(existingEvent);
                                LOGGER.info("Event updated with id: {}", updatedEvent.getId());
                                return updatedEvent;
                            }).map(EventMapper::toDto);
                        }));
    }

    @Override
    public Mono<Void> delete(Integer id) {
        return SecurityUtils.getCurrentUserName()
                .flatMap(currentUsername -> canAccessEvent(currentUsername)
                        .flatMap(canAccess -> {
                            if (!canAccess) {
                                LOGGER.warn("User {} attempted to delete event", currentUsername);
                                return Mono.error(new ValidationException("Only ADMIN can delete events"));
                            }
                            return ReactiveWrapper.voidOperation(() -> {
                                LOGGER.info("Event deleted with ID: {}", id);
                                eventRepository.deleteById(id);
                            });
                        }));
    }

    @Override
    public Mono<EventDto> findById(Integer id) {
        return SecurityUtils.getCurrentUserName()
                .flatMap(currentUsername -> canAccessEvent(currentUsername)
                        .flatMap(canAccess -> {
                            if (!canAccess) {
                                LOGGER.warn("User {} attempted to view event by id: {}", currentUsername, id);
                                return Mono.error(new ValidationException("Only ADMIN can view events"));
                            }
                            return ReactiveWrapper.monoOptional(() -> eventRepository.findById(id))
                                    .switchIfEmpty(Mono.error(new NotFoundException("Event not found with id: " + id)))
                                    .map(EventMapper::toDto)
                                    .doOnSuccess(event -> LOGGER.info("Event found: {}", event.getId()));
                        }));
    }

    @Override
    public Flux<EventDto> findAll() {
        return SecurityUtils.getCurrentUserName()
                .flatMapMany(currentUsername -> canAccessEvent(currentUsername)
                        .flatMapMany(canAccess -> {
                            if (!canAccess) {
                                LOGGER.warn("User {} attempted to view all events", currentUsername);
                                return Mono.error(new ValidationException("Only ADMIN can view all events"));
                            }
                            LOGGER.info("User {} requested all events", currentUsername);
                            return ReactiveWrapper.flux(eventRepository::findAll)
                                    .map(EventMapper::toDto);
                        }));
    }

    // --- Вспомогательные методы ---
    private Mono<Boolean> canAccessEvent(String username) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username))
                .map(user -> user.getRole().equals(UserRole.ADMIN))
                .defaultIfEmpty(false);
    }
}
