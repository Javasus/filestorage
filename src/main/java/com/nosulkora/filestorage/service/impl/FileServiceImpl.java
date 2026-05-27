package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.mapper.FileMapper;
import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.entity.User;
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
import org.springframework.http.codec.multipart.FilePart;
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
    public Mono<FileDto> create(FileDto fileDto, FilePart filePart) {
        return getCurrentUser()
                .flatMap(userDto -> {
                    validateFilename(fileDto.getName());
                    // userProxy для корректного сохранения file.
                    User userProxy = new User();
                    userProxy.setId(userDto.getId());
                    File file = FileMapper.toEntity(fileDto);
                    file.setUser(userProxy);
                    file.setStatus(FileStatus.ACTIVE);
                    return saveFile(file)
                            .flatMap(savedFile -> createEvent(userDto.getId(), savedFile.getId(), EventStatus.CREATED)
                                    .thenReturn(FileMapper.toDto(savedFile)));
                });
    }

    @Override
    public Mono<FileDto> update(FileDto fileDto) {
        return getCurrentUser()
                .flatMap(userDto -> {
                    validateFileId(fileDto.getId());
                    validateFilename(fileDto.getName());
                    // Проверяем существует ли файл в БД
                    return getFileIsExist(fileDto.getId())
                            .flatMap(existingFile -> checkFileAccess(existingFile, userDto))
                            .flatMap(existingFile -> {
                                existingFile.setName(fileDto.getName());
                                existingFile.setLocation(fileDto.getLocation());
                                existingFile.setStatus(
                                        fileDto.getStatus() != null ? fileDto.getStatus() : existingFile.getStatus());
                                return saveFile(existingFile)
                                        .flatMap(updatedFile ->
                                                createEvent(userDto.getId(), updatedFile.getId(), EventStatus.UPDATED)
                                                        .thenReturn(FileMapper.toDto(updatedFile)));
                            });
                });
    }

    @Override
    public Mono<Void> delete(Integer fileId) {
        return getCurrentUser()
                .flatMap(userDto -> {
                    validateFileId(fileId);
                    return getFileIsExist(fileId)
                            .flatMap(file -> checkFileAccess(file, userDto))
                            .flatMap(file -> deleteFileFromDb(fileId)
                                    .then(createEvent(userDto.getId(), fileId, EventStatus.DELETED)));
                });
    }

    @Override
    public Mono<FileDto> findById(Integer fileId) {
        return getCurrentUser()
                .flatMap(userDto -> {
                    validateFileId(fileId);
                    return getFileIfExist(fileId)
                            .flatMap(file -> checkFileAccess(file, userDto))
                            .map(FileMapper::toDto)
                            .doOnNext(fileDto -> LOGGER.debug(
                                    "File found: {} by user {}",
                                    fileDto.getName(),
                                    userDto.getUsername()));
                });
    }

    @Override
    public Flux<FileDto> findAll() {
        return getCurrentUser()
                .flatMapMany(userDto -> {
                    LOGGER.debug("User {} requested all files", userDto.getUsername());
                    // USER видит только все свои файлы.
                    if (userDto.getRole().equals(UserRole.USER)) {
                        return ReactiveWrapper.flux(() -> fileRepository.findByUserId(userDto.getId()))
                                .map(FileMapper::toDto);
                    }
                    // ADMIN & MODERATOR видят файлы всех пользователей.
                    return ReactiveWrapper.flux(fileRepository::findAll)
                            .map(FileMapper::toDto);
                });
    }

    // --- Вспомогательный методы ---

    /**
     * Возвращает текущего пользователя, который делает запрос.
     */
    private Mono<UserDto> getCurrentUser() {
        return SecurityUtils.getCurrentUserName()
                .flatMap(userService::findByUsername)
                .switchIfEmpty(Mono.error(new NotFoundException("User not found")));
    }

    /**
     * Проверка прав пользователя.
     *
     * @param file    Файл над которым совершается действие.
     * @param userDto Пользователь, который совершает изменения.
     * @return Файл над которым совершается действие.
     */
    private Mono<File> checkFileAccess(File file, UserDto userDto) {
        // Если роль USER, проверяем совпадение userId
        if (userDto.getRole().equals(UserRole.USER) && !userDto.getId().equals(file.getUser().getId())) {
            return Mono.error(new AccessDeniedException("You do not have permission to work with this file."));
        }
        // ADMIN и MODERATOR проходят проверку автоматически
        return Mono.just(file);
    }

    private void validateFileId(Integer id) {
        if (id == null) {
            LOGGER.error("File ID cannot be null");
            throw new ValidationException("File ID cannot be null");
        }
    }

    private void validateFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            LOGGER.error("File name cannot be empty");
            throw new ValidationException("File name cannot be empty");
        }
    }

    /**
     * Сохраняет файл, и создает и сохраняет событие.
     *
     * @param file файл который нужно сохранить в БД.
     * @return сохраненный файл.
     */
    private Mono<File> saveFile(File file) {
        return ReactiveWrapper.mono(() -> {
            File savedFile = fileRepository.save(file);
            LOGGER.info("File saved successfully with ID: {}", savedFile.getId());
            return savedFile;
        });
    }

    private Mono<Void> createEvent(Integer userId, Integer fileId, EventStatus eventStatus) {
        EventDto eventDto = new EventDto();
        eventDto.setUserId(userId);
        eventDto.setFileId(fileId);
        eventDto.setStatus(eventStatus);
        return eventService.create(eventDto)
                .doOnSuccess(v -> LOGGER.info("Event {} create for file ID: {}", eventStatus, fileId))
                .then();
    }

    private Mono<File> getFileIsExist(Integer fileId) {
        return ReactiveWrapper.monoOptional(() -> fileRepository.findById(fileId))
                .switchIfEmpty(Mono.error(new NotFoundException("File not found in DB with id: " + fileId)));
    }

    private Mono<Void> deleteFileFromDb(Integer fileId) {
        return ReactiveWrapper.voidOperation(() -> {
            fileRepository.deleteById(fileId);
            LOGGER.info("File record deleted from DB with ID: {}", fileId);
        });
    }

    private Mono<File> getFileIfExist(Integer fileId) {
        return ReactiveWrapper.monoOptional(() ->
                        fileRepository.findById(fileId))
                .switchIfEmpty(Mono.error(new NotFoundException("File not found in DB with id: " + fileId)));
    }
}
