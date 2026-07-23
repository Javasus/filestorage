package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.mapper.FileMapper;
import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.FileStatus;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.security.UserPrincipal;
import com.nosulkora.filestorage.service.EventService;
import com.nosulkora.filestorage.service.FileService;
import com.nosulkora.filestorage.service.StorageService;
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
    private final EventService eventService;
    private final StorageService storageService;

    public FileServiceImpl(
            FileRepository fileRepository,
            EventService eventService,
            StorageService storageService
    ) {
        this.fileRepository = fileRepository;
        this.eventService = eventService;
        this.storageService = storageService;
    }

    @Override
    public Mono<FileDto> create(FileDto fileDto, FilePart filePart) {
        return SecurityUtils.getCurrentUser()
                .flatMap(principal -> {
                    validateFilename(fileDto.getName());
                    // 1. Физически загружаем файл в MinIo
                    return storageService.uploadFile(filePart)
                            .flatMap(fileUrl -> {
                                // 2. Собираем сущность с полученным  location URL
                                User userProxy = new User(); // userProxy для корректного сохранения file.
                                userProxy.setId(principal.getId());

                                File file = FileMapper.toEntity(fileDto);
                                file.setUser(userProxy);
                                file.setLocation(fileUrl); // Записываем URL in S3
                                file.setStatus(FileStatus.ACTIVE);

                                // 3. Сохраняем в БД и регистрируем Event
                                return saveFile(file)
                                        .flatMap(savedFile ->
                                                createEvent(principal.getId(), savedFile.getId(), EventStatus.CREATED)
                                                        .thenReturn(FileMapper.toDto(savedFile)));
                            });
                });
    }

    @Override
    public Mono<FileDto> update(FileDto fileDto) {
        return SecurityUtils.getCurrentUser()
                .flatMap(principal -> {
                    validateFileId(fileDto.getId());
                    validateFilename(fileDto.getName());
                    // Проверяем существует ли файл в БД
                    return getFileIfExist(fileDto.getId())
                            .flatMap(existingFile -> checkFileAccess(existingFile, principal))
                            .flatMap(existingFile -> {
                                existingFile.setName(fileDto.getName());
                                return saveFile(existingFile)
                                        .flatMap(updatedFile ->
                                                createEvent(principal.getId(), updatedFile.getId(), EventStatus.UPDATED)
                                                        .thenReturn(FileMapper.toDto(updatedFile)));
                            });
                });
    }

    @Override
    public Mono<Void> delete(Integer fileId) {
        return SecurityUtils.getCurrentUser()
                .flatMap(principal -> {
                    validateFileId(fileId);

                    // 1. Находим файл для получения его Location URL и проверки прав
                    return getFileIfExist(fileId)
                            .flatMap(file -> checkFileAccess(file, principal))
                            .flatMap(existingFile -> {
                                // 2. Меняем статус у файла и обновляем его в БД
                                existingFile.setStatus(FileStatus.ARCHIVED);
                                return saveFile(existingFile)
                                        // 3. Создаём event об удалении
                                        .flatMap(updatedFile ->
                                                createEvent(principal.getId(), fileId, EventStatus.DELETED))
                                        .then();
                            });
                });
    }

    @Override
    public Mono<FileDto> findById(Integer fileId) {
        return SecurityUtils.getCurrentUser()
                .flatMap(principal -> {
                    validateFileId(fileId);
                    return getFileIfExist(fileId)
                            .flatMap(file -> checkFileAccess(file, principal))
                            .map(file -> FileMapper.toDto((File) file));
                });
    }

    @Override
    public Flux<FileDto> findAll() {
        return SecurityUtils.getCurrentUser()
                .flatMapMany(principal -> {
                    LOGGER.debug("User {} requested all files", principal.getName());
                    // USER видит только все свои файлы.
                    if (principal.getRole().equals(UserRole.USER)) {
                        return ReactiveWrapper.<File>flux(() -> fileRepository.findByUserId(principal.getId()))
                                .map(FileMapper::toDto);
                    }
                    // ADMIN & MODERATOR видят файлы всех пользователей.
                    return ReactiveWrapper.<File>flux(fileRepository::findAll)
                            .map(FileMapper::toDto);
                });
    }

    // --- Вспомогательный методы ---

    /**
     * Проверка прав пользователя.
     *
     * @param file          Файл над которым совершается действие.
     * @param userPrincipal Пользователь, который совершает изменения.
     * @return Файл над которым совершается действие.
     */
    private Mono<File> checkFileAccess(File file, UserPrincipal userPrincipal) {
        // Если роль USER, проверяем совпадение userId
        if (userPrincipal.getRole().equals(UserRole.USER) && !userPrincipal.getId().equals(file.getUser().getId())) {
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

    private Mono<File> getFileIfExist(Integer fileId) {
        return ReactiveWrapper.monoOptional(() ->
                        fileRepository.findById(fileId))
                .switchIfEmpty(Mono.error(new NotFoundException("File not found in DB with id: " + fileId)));
    }
}
