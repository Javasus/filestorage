package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.mapper.FileMapper;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.FileStatus;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.repository.EventRepository;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.security.UserPrincipal;
import com.nosulkora.filestorage.service.FileSecurityChecker;
import com.nosulkora.filestorage.service.FileService;
import com.nosulkora.filestorage.service.StorageService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileServiceImpl.class);

    private final FileRepository fileRepository;
    private final StorageService storageService;
    private final FileSecurityChecker fileSecurityChecker;
    private final EventRepository eventRepository;

    public FileServiceImpl(
            FileRepository fileRepository,
            StorageService storageService,
            FileSecurityChecker fileSecurityChecker,
            EventRepository eventRepository
    ) {
        this.fileRepository = fileRepository;
        this.storageService = storageService;
        this.fileSecurityChecker = fileSecurityChecker;
        this.eventRepository = eventRepository;
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

                                // 3. Сохраняем файл и регистрируем Event в рамках одной транзакции
                                return ReactiveWrapper.<File>mono(() -> saveFileAndEventInTransaction(
                                                file,
                                                EventStatus.CREATED,
                                                principal))
                                        .map(FileMapper::toDto)
                                        // В случае ошибки сохранения файла или события, удаляем файл из MinIO
                                        .onErrorResume(databaseException -> {
                                            LOGGER.error("Database commit failed! Initiating S3 rollback. " +
                                                         "Deleting file from MinIO: {}", fileUrl);
                                            return storageService.deleteFile(fileUrl)
                                                    .then(Mono.error(databaseException));
                                        });
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
                            .flatMap(existingFile -> fileSecurityChecker.checkWriteAccess(existingFile, principal))
                            .flatMap(existingFile -> {
                                existingFile.setName(fileDto.getName());
                                return ReactiveWrapper.mono(() -> saveFileAndEventInTransaction(
                                                existingFile,
                                                EventStatus.UPDATED,
                                                principal))
                                        .map(file -> FileMapper.toDto((File) file));
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
                            .flatMap(file -> fileSecurityChecker.checkWriteAccess(file, principal))
                            .flatMap(existingFile -> {
                                // 2. Меняем статус у файла и обновляем его в БД
                                existingFile.setStatus(FileStatus.ARCHIVED);
                                return ReactiveWrapper.mono(() -> saveFileAndEventInTransaction(
                                                existingFile,
                                                EventStatus.DELETED,
                                                principal))
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
                            .flatMap(file -> fileSecurityChecker.checkReadAccess(file, principal))
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

    // --------------------------------------- Вспомогательные методы --------------------------------------------------

    /**
     * Сохранение файла и создание событие в рамках одной транзакции СУБД.
     *
     * @param file        файл который нужно сохранить в БД.
     * @param eventStatus Статус события.
     * @return сохраненный файл.
     */
    @Transactional
    public File saveFileAndEventInTransaction(File file, EventStatus eventStatus, UserPrincipal principal) {
        // 1. Сохраняем файл
        File savedFile = fileRepository.save(file);
        LOGGER.info("File entity committed to DB with ID: {}", savedFile.getId());

        // 2. Создаем промежуточного пользователя для указания его в событии, что бы видеть того кто изменяет файл.
        User userProxy = new User();
        userProxy.setId(principal.getId());
        userProxy.setRole(principal.getRole());
        userProxy.setUsername(principal.getName());

        // 2. Создаём и сохраняем связанное событие
        Event event = new Event();
        event.setUser(userProxy);
        event.setFile(savedFile);
        event.setStatus(eventStatus);
        event.setTimeStamp(LocalDateTime.now());

        eventRepository.save(event);
        LOGGER.info("Event log [{}] committed to DB for file ID: {}", eventStatus, savedFile.getId());

        return savedFile;
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

    private Mono<File> getFileIfExist(Integer fileId) {
        return ReactiveWrapper.monoOptional(() ->
                        fileRepository.findById(fileId))
                .switchIfEmpty(Mono.error(new NotFoundException("File not found in DB with id: " + fileId)));
    }
}
