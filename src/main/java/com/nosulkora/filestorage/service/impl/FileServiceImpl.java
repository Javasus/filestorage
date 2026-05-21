package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.mapper.FileMapper;
import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.FileStatus;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.service.EventService;
import com.nosulkora.filestorage.service.FileService;
import com.nosulkora.filestorage.service.UserService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileServiceImpl.class);

    private final FileRepository fileRepository;
    private final UserService userService;
    private final EventService eventService;

    public FileServiceImpl(
            FileRepository fileRepository,
            UserService userService,
            EventService eventService
    ) {
        this.fileRepository = fileRepository;
        this.userService = userService;
        this.eventService = eventService;
    }

    @Override
    public Mono<FileDto> create(FileDto fileDto) {
        return getCurrentUserWithAccessCheck()
                .flatMap(userDto -> {
                    validateFileId(fileDto.getId());
                    validateFilename(fileDto.getName());
                    File file = FileMapper.toEntity(fileDto);
                    file.setStatus(FileStatus.ACTIVE);
                    return saveFileAndCreateEvent(file, userDto.getId(), EventStatus.CREATED);
                });
    }

    @Override
    public Mono<FileDto> update(FileDto fileDto) {
        return getCurrentUserWithAccessCheck()
                .flatMap(userDto -> {
                    validateFileId(fileDto.getId());
                    validateFilename(fileDto.getName());
                    // Проверяем существует ли файл в БД
                    return getFileIsExist(fileDto.getId())
                            .flatMap(existingFile -> {
                                existingFile.setName(fileDto.getName());
                                existingFile.setLocation(fileDto.getLocation());
                                existingFile.setStatus(
                                        fileDto.getStatus() != null ? fileDto.getStatus() : existingFile.getStatus());

                                return saveFileAndCreateEvent(existingFile, userDto.getId(), EventStatus.UPDATED);
                            });
                });
    }

    @Override
    public Mono<Void> delete(Integer fileId) {
        return getCurrentUserWithAccessCheck()
                .flatMap(userDto -> {
                    validateFileId(fileId);
                    return getFileIsExist(fileId)
                            .flatMap(file -> deleteFileAnrCreateEvent(fileId, userDto.getId(), file.getName()));
                });
    }

    @Override
    public Mono<FileDto> findById(Integer fileId) {
        return getCurrentUserWithAccessCheck()
                .flatMap(userDto -> {
                    validateFileId(fileId);
                    return getFileIfExist(fileId)
                            .map(FileMapper::toDto)
                            .doOnNext(fileDto -> LOGGER.debug(
                                    "File found: {} by user {}",
                                    fileDto.getName(),
                                    userDto.getUsername()));
                });
    }

    @Override
    public Flux<FileDto> findAll() {
        return getCurrentUserWithAccessCheck()
                .flatMapMany(userDto -> {
                    LOGGER.debug("User {} requested all files", userDto.getUsername());
                    return ReactiveWrapper.flux(fileRepository::findAll)
                            .map(FileMapper::toDto);
                });
    }

    // --- Вспомогательный методы ---

    private Mono<UserDto> getCurrentUserWithAccessCheck() {
        return SecurityUtils.getCurrentUserName()
                .flatMap(userService::findByUsername)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")))
                .flatMap(userDto -> {
                    if (userDto.getRole().equals(UserRole.USER)) {
                        return Mono.just(userDto);
                    }
                    return Mono.just(userDto);
                });
    }

    private void validateFileId(Integer id) {
        if (id == null) {
            throw new ValidationException("File ID cannot be null");
        }
    }

    private void validateFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new ValidationException("File name cannot be empty");
        }
    }

    /**
     * Сохраняет файл, и создает и сохраняет событие.
     *
     * @param file        файл который нужно сохранить в БД.
     * @param userId      ID пользователя для сохранения Event.
     * @param eventStatus Статус event.
     * @return сохраненный файл.
     */
    private Mono<FileDto> saveFileAndCreateEvent(File file, Integer userId, EventStatus eventStatus) {
        return ReactiveWrapper.mono(() -> {
            File savedFile = fileRepository.save(file);
            LOGGER.info(
                    "File {} with id: {}",
                    eventStatus == EventStatus.CREATED ? "created" : "updated",
                    savedFile.getId()
            );
            return savedFile;
        }).flatMap(savedFile -> createEvent(userId, savedFile.getId(), eventStatus)
                .thenReturn(FileMapper.toDto(savedFile)));

    }

    private Mono<Void> createEvent(Integer userId, Integer fileId, EventStatus eventStatus) {
        EventDto eventDto = new EventDto();
        eventDto.setUserId(userId);
        eventDto.setFileId(fileId);
        eventDto.setStatus(eventStatus);
        return eventService.create(eventDto).then();
    }

    private Mono<File> getFileIsExist(Integer fileId) {
        return ReactiveWrapper.monoOptional(() -> fileRepository.findById(fileId)).switchIfEmpty(
                Mono.error(new NotFoundException("File not found in DB with id: " + fileId)));
    }

    private Mono<Void> deleteFileAnrCreateEvent(Integer fileId, Integer userId, String filename) {
        return ReactiveWrapper.voidOperation(() -> {
            fileRepository.deleteById(fileId);
            LOGGER.info("File deleted: {} with ID: {}", filename, fileId);
        }).then(createEvent(userId, fileId, EventStatus.DELETED));
    }

    private Mono<File> getFileIfExist(Integer fileId) {
        return ReactiveWrapper.monoOptional(() ->
                        fileRepository.findById(fileId))
                .switchIfEmpty(Mono.error(new NotFoundException("File not found with id: " + fileId)));
    }
}
