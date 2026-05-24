package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
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
        return Mono.zip(getUserIfExist(eventDto.getUserId()), getFileIfExist(eventDto.getFileId()))
                .flatMap(tuple -> {
                    User user = tuple.getT1();
                    File file = tuple.getT2();

                    Event event = new Event();
                    event.setUser(user);
                    event.setFile(file);
                    event.setStatus(eventDto.getStatus() != null ? eventDto.getStatus() : EventStatus.CREATED);
                    event.setTimeStamp(LocalDateTime.now());
                    return saveEventEntity(event);
                })
                .map(EventMapper::toDto)
                .doOnSuccess(saved -> LOGGER.info("Event saved successfully with ID: {}", saved.getId()));
    }

    @Override
    public Mono<EventDto> update(EventDto eventDto) {
        return checkEventManagerAccess()
                .then(getEventIfExist(eventDto.getId()))
                .flatMap(existingEvent -> {
                    if (eventDto.getStatus() != null) {
                        existingEvent.setStatus(EventStatus.UPDATED);
                    }
                    existingEvent.setTimeStamp(LocalDateTime.now());
                    return saveEventEntity(existingEvent);
                })
                .map(EventMapper::toDto)
                .doOnSuccess(updated -> LOGGER.info("Event updated successfully with ID: {}", updated.getId()));
    }

    @Override
    public Mono<Void> delete(Integer id) {
        return checkEventManagerAccess()
                .then(getEventIfExist(id)) // Проверяем, что событие существует.
                .flatMap(event -> ReactiveWrapper.voidOperation(() -> eventRepository.deleteById(id)))
                .doOnSuccess(v -> LOGGER.info("Event deleted successfully with ID: {}", id));
    }

    @Override
    public Mono<EventDto> findById(Integer id) {
        return checkEventManagerAccess()
                .then(getEventIfExist(id))
                .map(EventMapper::toDto)
                .doOnNext(dto -> LOGGER.info("Event retrieved: ID {}", dto.getId()));
    }

    @Override
    public Flux<EventDto> findAll() {
        return checkEventManagerAccess()
                .thenMany(Flux.defer(() -> {
                    LOGGER.info("Fetching all events from database");
                    return ReactiveWrapper.flux(eventRepository::findAll)
                            .map(EventMapper::toDto);
                }));
    }

    // --- Вспомогательные методы ---

    private Mono<User> getUserIfExist(Integer userId) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findById(userId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found by ID: " + userId)));
    }

    private Mono<File> getFileIfExist(Integer fileId) {
        return ReactiveWrapper.monoOptional(() -> fileRepository.findById(fileId))
                .switchIfEmpty(Mono.error(new NotFoundException("File not found by ID: " + fileId)));
    }

    private Mono<Event> getEventIfExist(Integer eventId) {
        return ReactiveWrapper.monoOptional(() -> eventRepository.findById(eventId))
                .switchIfEmpty(Mono.error(new NotFoundException("Event not found by ID: " + eventId)));
    }

    private Mono<Void> checkEventManagerAccess() {
        return SecurityUtils.getCurrentUserName()
                .flatMap(username -> ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username)))
                .switchIfEmpty(Mono.error(new NotFoundException("Current authenticated user not found in DB")))
                .flatMap(currentUser -> {
                    UserRole role = currentUser.getRole();
                    if (role.equals(UserRole.ADMIN) || role.equals(UserRole.MODERATOR)) {
                        return Mono.empty();
                    }
                    LOGGER.warn("Access denied: User {} with role {} attempted to manage events",
                            currentUser.getUsername(), role);
                    return Mono.error(new AccessDeniedException("Only MODERATOR or ADMIN can manage events"));
                });
    }

    private Mono<Event> saveEventEntity(Event event) {
        return ReactiveWrapper.mono(() -> eventRepository.save(event));
    }
}
