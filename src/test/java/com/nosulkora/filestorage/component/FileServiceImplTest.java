package com.nosulkora.filestorage.component;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
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
import com.nosulkora.filestorage.service.StorageService;
import com.nosulkora.filestorage.service.impl.FileServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class FileServiceImplTest {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private FileSecurityChecker fileSecurityChecker;

    @InjectMocks
    private FileServiceImpl fileService;

    private MockedStatic<SecurityUtils> mockedSecurityUtils;

    @BeforeEach
    void setUp() {
        // Открываем мок для статических методов SecurityUtils перед каждым тестом
        mockedSecurityUtils = Mockito.mockStatic(SecurityUtils.class);
    }

    @AfterEach
    void tearDown() {
        // Обязательно закрываем статический мок во избежание утечек памяти
        mockedSecurityUtils.close();
    }

    @Test
    @DisplayName("Успешное сохранение файла")
    void createFileSuccessTest() {
        FileDto fileDto = new FileDto();
        fileDto.setName("filename");

        FilePart filePart = mock(FilePart.class);
        UserPrincipal principal = new UserPrincipal(1, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        String expectedS3Url = "http://minio/bucket/test.txt";
        File file = new File();
        file.setId(100);
        file.setName("test.txt");
        file.setLocation(expectedS3Url);
        file.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(1);
        file.setUser(userProxy);

        Event mockEvent = new Event();
        mockEvent.setId(123);
        mockEvent.setUser(userProxy);
        mockEvent.setFile(file);
        mockEvent.setStatus(EventStatus.CREATED);

        when(storageService.uploadFile(filePart)).thenReturn(Mono.just(expectedS3Url));
        when(fileRepository.save(any(File.class))).thenReturn(file);
        when(eventRepository.save(any(Event.class))).thenReturn(mockEvent);

        Mono<FileDto> result = fileService.create(fileDto, filePart);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(100)
                                          && dto.getName().equals("test.txt")
                                          && dto.getLocation().equals(expectedS3Url))
                .verifyComplete();

        verify(storageService, times(1)).uploadFile(filePart);
        verify(fileRepository, times(1)).save(any(File.class));
        verify(eventRepository, times(1)).save(
                argThat(event ->
                        event.getStatus().equals(EventStatus.CREATED) &&
                        event.getFile().getId().equals(100) &&
                        event.getUser().getId().equals(1)
                )
        );
    }

    @Test
    @DisplayName("Ошибка загрузки файла: пустое имя файла")
    void createFilenameNotFoundAccessDeniedTest() {
        FileDto fileDto = new FileDto();
        fileDto.setName("  ");

        FilePart filePart = mock(FilePart.class);
        UserPrincipal principal = new UserPrincipal(1, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<FileDto> result = fileService.create(fileDto, filePart);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof ValidationException
                                     && throwable.getMessage().equals("File name cannot be empty")
                ).verify();

        verify(storageService, never()).uploadFile(filePart);
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка создания файла: сбой СУБД активирует компенсационное удаление из MinIO")
    void createFileTransactionDbErrorTest() {
        FileDto fileDto = new FileDto();
        fileDto.setName("test-file.txt");
        String uploadedS3Url = "http://minio/bucket/zombi-file.txt";
        String errorMessage = "Database Connection Timeout!";

        FilePart filePart = mock(FilePart.class);
        UserPrincipal principal = new UserPrincipal(1, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(storageService.uploadFile(filePart)).thenReturn(Mono.just(uploadedS3Url));
        when(fileRepository.save(any(File.class))).
                thenThrow(new RuntimeException(errorMessage));
        when(storageService.deleteFile(uploadedS3Url)).thenReturn(Mono.empty());

        Mono<FileDto> result = fileService.create(fileDto, filePart);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException
                && throwable.getMessage().equals(errorMessage))
                .verify();

        verify(storageService, times(1)).uploadFile(filePart);
        verify(fileRepository, times(1)).save(any(File.class));
        verify(storageService, times(1)).deleteFile(uploadedS3Url);
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("""
            Успешное изменение файла ADMIN.\
            1. Название файла в БД меняется.\
            2. Путь до файла и статус не меняются.""")
    void updateFileAdminSuccessTest() {
        String location = "http://minio/hacker-url/test.txt";
        String newFilename = "filename";
        FileDto fileDto = new FileDto();
        fileDto.setId(1);
        fileDto.setName(newFilename);
        fileDto.setLocation(location);
        fileDto.setStatus(FileStatus.ARCHIVED);

        String expectedS3Url = "http://minio/bucket/test.txt";
        File existingFile = new File();
        existingFile.setId(1);
        existingFile.setName("test.txt");
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(5);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(5, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));
        when(fileRepository.findById(fileDto.getId())).thenReturn(Optional.of(existingFile));
        when(fileRepository.save(any(File.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(Event.class))).thenReturn(new Event());

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getName().equals(newFilename)             // Имя изменилось
                                          && dto.getLocation().equals(expectedS3Url)    // Локация СТАРАЯ
                                          && dto.getStatus().equals(FileStatus.ACTIVE)  // Статус СТАРЫЙ
                )
                .verifyComplete();
        verify(fileRepository, times(1)).findById(fileDto.getId());
        verify(fileRepository, times(1)).save(any(File.class));
        verify(eventRepository, times(1)).save(
                argThat(eventDto ->
                        eventDto.getStatus().equals(EventStatus.UPDATED) &&
                        eventDto.getFile().getId().equals(1) &&
                        eventDto.getUser().getId().equals(5)
                )
        );
    }

    @Test
    @DisplayName("""
            Успешное изменение файла MODERATOR.\
            1. Название файла в БД меняется.\
            2. Путь до файла и статус не меняются.""")
    void updateFileModeratorSuccessTest() {
        String location = "http://minio/hacker-url/test.txt";
        String newFilename = "filename";
        FileDto fileDto = new FileDto();
        fileDto.setId(1);
        fileDto.setName(newFilename);
        fileDto.setLocation(location);
        fileDto.setStatus(FileStatus.ARCHIVED);

        String expectedS3Url = "http://minio/bucket/test.txt";
        File existingFile = new File();
        existingFile.setId(1);
        existingFile.setName("test.txt");
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(5);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(5, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));
        when(fileRepository.findById(fileDto.getId())).thenReturn(Optional.of(existingFile));
        when(fileRepository.save(any(File.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(Event.class))).thenReturn(new Event());

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getName().equals(newFilename)             // Имя изменилось
                                          && dto.getLocation().equals(expectedS3Url)    // Локация СТАРАЯ
                                          && dto.getStatus().equals(FileStatus.ACTIVE)  // Статус СТАРЫЙ
                )
                .verifyComplete();
        verify(fileRepository, times(1)).findById(fileDto.getId());
        verify(fileRepository, times(1)).save(any(File.class));
        verify(eventRepository, times(1)).save(
                argThat(eventDto ->
                        eventDto.getStatus().equals(EventStatus.UPDATED) &&
                        eventDto.getFile().getId().equals(1) &&
                        eventDto.getUser().getId().equals(5)
                )
        );
    }

    @Test
    @DisplayName("Ошибка изменения файла: обычный USER получает AccessDeniedException")
    void updateFileAccessDeniedForUserTest() {
        FileDto fileDto = new FileDto();
        fileDto.setId(1);
        fileDto.setName("file_name");

        File existingFile = new File();
        existingFile.setId(1);

        UserPrincipal principal = new UserPrincipal(5, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileDto.getId())).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.error(new AccessDeniedException("Access Denied")));

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectError(AccessDeniedException.class)
                .verify();
        // Проверяем, что до сохранения файла в БД и генерации события дело не дошло.
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка изменения файла: В новом файле отсутствует ID ")
    void updateFileWithoutIdNegativeTest() {
        FileDto fileDto = new FileDto();

        UserPrincipal principal = new UserPrincipal(5, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof ValidationException
                                     && throwable.getMessage().equals("File ID cannot be null")
                ).verify();

        verify(fileRepository, never()).findById(fileDto.getId());
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка изменения файла: В новом файле отсутствует filename")
    void updateFileWithoutFilenameNegativeTest() {
        FileDto fileDto = new FileDto();
        fileDto.setId(2);

        UserPrincipal principal = new UserPrincipal(5, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof ValidationException
                                     && throwable.getMessage().equals("File name cannot be empty")
                ).verify();

        verify(fileRepository, never()).findById(fileDto.getId());
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка изменения файла ADMIN: Файл с таким ID не найден в БД")
    void updateFileNotFoundInDbNegativeTest() {
        FileDto fileDto = new FileDto();
        fileDto.setId(2);
        fileDto.setName("newName");

        UserPrincipal principal = new UserPrincipal(5, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileDto.getId())).thenReturn(Optional.empty());

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("File not found in DB with id: " + fileDto.getId())
                ).verify();

        verify(fileRepository, times(1)).findById(fileDto.getId());
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка изменения файла: обычный USER получает отказ при любой попытке обновления")
    void updateForeignFileAsUserAccessDeniedTest() {
        FileDto fileDto = new FileDto();
        fileDto.setId(2);
        fileDto.setName("newName");

        File existingFile = new File();
        existingFile.setId(1);
        existingFile.setName("test.txt");

        User userProxy = new User();
        userProxy.setId(5);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(100, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileDto.getId())).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.error(new AccessDeniedException("You do not have permission to work with this file.")));

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectError(AccessDeniedException.class)
                .verify();

        verify(fileRepository, times(1)).findById(fileDto.getId());
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка обновления файла: сбой СУБД при сохранении Event отменяет всю операцию")
    void updateFileTransactionDbErrorTest() {
        Integer fileId = 23;
        FileDto fileDto = new FileDto();
        fileDto.setId(fileId);
        fileDto.setName("new_filename_test-file.txt");

        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName("old_filename_test-file.txt");
        existingFile.setLocation("http://minio/bucket/file.txt");

        String errorMessage = "JPA Transaction Constraint Violation!";

        User userProxy = new User();
        userProxy.setId(5);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(16, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));
        when(fileRepository.save(any(File.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Падаем на сохранении события.
        when(eventRepository.save(any(Event.class)))
                .thenThrow(new RuntimeException(errorMessage));

        Mono<FileDto> result = fileService.update(fileDto);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException
                && throwable.getMessage().equals(errorMessage))
                .verify();

        verify(fileRepository, times(1)).findById(fileId);
        verify(fileSecurityChecker, times(1))
                .checkWriteAccess(any(File.class), any(UserPrincipal.class));
        verify(fileRepository, times(1)).save(any(File.class));
        verify(eventRepository, times(1)).save(any(Event.class));
        verify(storageService, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("Ошибка удаления файла: обычный USER получает отказ при попытке удаления своего файла")
    void deleteFileAsUserSuccessTest() {
        Integer fileId = 33;

        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName("test.txt");

        User userProxy = new User();
        userProxy.setId(5);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(5, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.error(new AccessDeniedException("Users are not allowed to delete files.")));

        Mono<Void> result = fileService.delete(fileId);

        StepVerifier.create(result)
                .expectError(AccessDeniedException.class)
                .verify();

        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("ADMIN успешно удаляет файл пользователя")
    void deleteForeignFileAsAdminSuccessTest() {
        Integer fileId = 33;

        String expectedS3Url = "http://minio/bucket/test.txt";
        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName("test.txt");
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(5);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(777, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));
        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileRepository.save(any(File.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(Event.class))).thenReturn(new Event());

        Mono<Void> result = fileService.delete(fileId);

        StepVerifier.create(result).verifyComplete();

        verify(fileRepository, times(1)).findById(fileId);
        verify(fileRepository, times(1)).save(
                argThat(fileDto ->
                        fileDto.getId().equals(fileId) &&
                        fileDto.getStatus().equals(FileStatus.ARCHIVED) &&
                        fileDto.getUser().getId().equals(userProxy.getId())
                )
        );
        verify(eventRepository, times(1)).save(
                argThat(eventDto ->
                        eventDto.getStatus().equals(EventStatus.DELETED) &&
                        eventDto.getFile().getId().equals(fileId) &&
                        eventDto.getUser().getId().equals(principal.getId())
                )
        );
    }

    @Test
    @DisplayName("MODERATOR успешно удаляет файл пользователя")
    void deleteForeignFileAsModeratorSuccessTest() {
        Integer fileId = 33;

        String expectedS3Url = "http://minio/bucket/test.txt";
        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName("test.txt");
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(5);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(16, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileSecurityChecker.checkWriteAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));
        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileRepository.save(any(File.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(eventRepository.save(any(Event.class))).thenReturn(new Event());

        Mono<Void> result = fileService.delete(fileId);

        StepVerifier.create(result).verifyComplete();

        verify(fileRepository, times(1)).findById(fileId);
        verify(fileRepository, times(1)).save(
                argThat(fileDto ->
                        fileDto.getId().equals(fileId) &&
                        fileDto.getStatus().equals(FileStatus.ARCHIVED) &&
                        fileDto.getUser().getId().equals(userProxy.getId())
                )
        );
        verify(eventRepository, times(1)).save(
                argThat(eventDto ->
                        eventDto.getStatus().equals(EventStatus.DELETED) &&
                        eventDto.getFile().getId().equals(fileId) &&
                        eventDto.getUser().getId().equals(principal.getId())
                )
        );
    }

    @Test
    @DisplayName("Ошибка при удалении файла: ADMIN не указал id файла")
    void deleteFileIdNotFoundNegativeTest() {
        UserPrincipal principal = new UserPrincipal(1, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<Void> result = fileService.delete(null);


        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof ValidationException
                                     && throwable.getMessage().equals("File ID cannot be null")
                ).verify();

        verify(fileRepository, never()).findById(any(Integer.class));
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка при удалении файла: по предоставленному id файл не найден в БД")
    void deleteFileNotFoundInDbNegativeTest() {
        Integer fileId = 33;
        UserPrincipal principal = new UserPrincipal(1, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.empty());

        Mono<Void> result = fileService.delete(fileId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("File not found in DB with id: " + fileId)
                ).verify();

        verify(fileRepository, times(1)).findById(fileId);
        verify(fileRepository, never()).save(any(File.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("USER успешно ищет свой файл по ID")
    void findByIdFileAsUserSuccessTest() {
        Integer fileId = 33;

        String expectedS3Url = "http://minio/bucket/test.txt";
        String filename = "test.txt";
        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName(filename);
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(1);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(1, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkReadAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));

        Mono<FileDto> result = fileService.findById(fileId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(fileId) &&
                                          dto.getStatus().equals(FileStatus.ACTIVE) &&
                                          dto.getName().equals(filename) &&
                                          dto.getUserId().equals(userProxy.getId()))
                .verifyComplete();

        verify(fileRepository, times(1)).findById(fileId);
    }

    @Test
    @DisplayName("ADMIN успешно ищет чужой файл по ID")
    void findByIdForeignFileAsAdminSuccessTest() {
        Integer fileId = 33;

        String expectedS3Url = "http://minio/bucket/test.txt";
        String filename = "test.txt";
        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName(filename);
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(1);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(777, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkReadAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));

        Mono<FileDto> result = fileService.findById(fileId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(fileId) &&
                                          dto.getStatus().equals(FileStatus.ACTIVE) &&
                                          dto.getName().equals(filename) &&
                                          dto.getUserId().equals(userProxy.getId()))
                .verifyComplete();

        verify(fileRepository, times(1)).findById(fileId);
    }

    @Test
    @DisplayName("MODERATOR успешно ищет чужой файл по ID")
    void findByIdForeignFileAsModeratorSuccessTest() {
        Integer fileId = 33;

        String expectedS3Url = "http://minio/bucket/test.txt";
        String filename = "test.txt";
        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName(filename);
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(1);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(16, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkReadAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.just(existingFile));

        Mono<FileDto> result = fileService.findById(fileId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(fileId) &&
                                          dto.getStatus().equals(FileStatus.ACTIVE) &&
                                          dto.getName().equals(filename) &&
                                          dto.getUserId().equals(userProxy.getId()))
                .verifyComplete();

        verify(fileRepository, times(1)).findById(fileId);
    }

    @Test
    @DisplayName("Не успешный поиск своего файла по ID: USER не указал ID файла")
    void findByIdFileIdNotFoundAsUserNegativeTest() {

        UserPrincipal principal = new UserPrincipal(1, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<FileDto> result = fileService.findById(null);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof ValidationException
                                     && throwable.getMessage().equals("File ID cannot be null")
                ).verify();

        verify(fileRepository, never()).findById(any(Integer.class));
    }

    @Test
    @DisplayName("Не успешный поиск своего файла по ID USER: файл с таким ID отсутствует в БД")
    void findByIdFileNotFoundInDbAsUserNegativeTest() {
        Integer fileId = 33;

        UserPrincipal principal = new UserPrincipal(1, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.empty());

        Mono<FileDto> result = fileService.findById(fileId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("File not found in DB with id: " + fileId)
                ).verify();

        verify(fileRepository, times(1)).findById(fileId);
    }

    @Test
    @DisplayName("Не успешный поиск файла по ID: USER ищет чужой файл")
    void findByIdForeignFileAsUserAccessDeniedTest() {
        Integer fileId = 33;

        String expectedS3Url = "http://minio/bucket/test.txt";
        String filename = "test.txt";
        File existingFile = new File();
        existingFile.setId(fileId);
        existingFile.setName(filename);
        existingFile.setLocation(expectedS3Url);
        existingFile.setStatus(FileStatus.ACTIVE);

        User userProxy = new User();
        userProxy.setId(1);
        existingFile.setUser(userProxy);

        UserPrincipal principal = new UserPrincipal(55, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findById(fileId)).thenReturn(Optional.of(existingFile));
        when(fileSecurityChecker.checkReadAccess(any(File.class), any(UserPrincipal.class)))
                .thenReturn(Mono.error(new AccessDeniedException("You do not have permission to view this file.")));

        Mono<FileDto> result = fileService.findById(fileId);

        StepVerifier.create(result)
                .expectError(AccessDeniedException.class)
                .verify();

        verify(fileRepository, times(1)).findById(fileId);
    }

    @Test
    @DisplayName("ADMIN возвращает список всех файлов")
    void findAllFileAsAdminSuccessTest() {
        User user1 = new User();
        user1.setId(111);

        File file1 = new File();
        file1.setId(1);
        file1.setUser(user1);
        file1.setName("file1");
        file1.setLocation("http://minio/bucket/file1.txt");
        file1.setStatus(FileStatus.ACTIVE);


        User user2 = new User();
        user2.setId(222);
        File file2 = new File();
        file2.setId(2);
        file2.setUser(user2);
        file2.setName("file2");
        file2.setLocation("http://minio/bucket/file2.txt");
        file2.setStatus(FileStatus.ARCHIVED);

        UserPrincipal principal = new UserPrincipal(777, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findAll()).thenReturn(List.of(file1, file2));

        Flux<FileDto> result = fileService.findAll();

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(1) &&
                                          dto.getUserId().equals(user1.getId()) &&
                                          dto.getName().equals("file1") &&
                                          dto.getLocation().equals("http://minio/bucket/file1.txt") &&
                                          dto.getStatus().equals(FileStatus.ACTIVE))
                .expectNextMatches(dto -> dto.getId().equals(2) &&
                                          dto.getUserId().equals(user2.getId()) &&
                                          dto.getName().equals("file2") &&
                                          dto.getLocation().equals("http://minio/bucket/file2.txt") &&
                                          dto.getStatus().equals(FileStatus.ARCHIVED))
                .verifyComplete();

        verify(fileRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("MODERATOR возвращает список всех файлов")
    void findAllFileAsModeratorSuccessTest() {
        User user1 = new User();
        user1.setId(111);

        File file1 = new File();
        file1.setId(1);
        file1.setUser(user1);
        file1.setName("file1");
        file1.setLocation("http://minio/bucket/file1.txt");
        file1.setStatus(FileStatus.ACTIVE);


        User user2 = new User();
        user2.setId(222);
        File file2 = new File();
        file2.setId(2);
        file2.setUser(user2);
        file2.setName("file2");
        file2.setLocation("http://minio/bucket/file2.txt");
        file2.setStatus(FileStatus.ARCHIVED);

        UserPrincipal principal = new UserPrincipal(16, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findAll()).thenReturn(List.of(file1, file2));

        Flux<FileDto> result = fileService.findAll();

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(1) &&
                                          dto.getUserId().equals(user1.getId()) &&
                                          dto.getName().equals("file1") &&
                                          dto.getLocation().equals("http://minio/bucket/file1.txt") &&
                                          dto.getStatus().equals(FileStatus.ACTIVE))
                .expectNextMatches(dto -> dto.getId().equals(2) &&
                                          dto.getUserId().equals(user2.getId()) &&
                                          dto.getName().equals("file2") &&
                                          dto.getLocation().equals("http://minio/bucket/file2.txt") &&
                                          dto.getStatus().equals(FileStatus.ARCHIVED))
                .verifyComplete();

        verify(fileRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("USER просматривает все свои файлы")
    void findAllFileAsUserAccessDeniedTest() {
        User user1 = new User();
        user1.setId(1);

        File file1 = new File();
        file1.setId(1);
        file1.setUser(user1);
        file1.setName("file1");
        file1.setLocation("http://minio/bucket/file1.txt");
        file1.setStatus(FileStatus.ACTIVE);

        File file2 = new File();
        file2.setId(2);
        file2.setUser(user1);
        file2.setName("file2");
        file2.setLocation("http://minio/bucket/file2.txt");
        file2.setStatus(FileStatus.ARCHIVED);

        UserPrincipal principal = new UserPrincipal(1, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(fileRepository.findByUserId(principal.getId())).thenReturn(List.of(file1, file2));

        Flux<FileDto> result = fileService.findAll();

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(1) &&
                                          dto.getUserId().equals(user1.getId()) &&
                                          dto.getName().equals("file1") &&
                                          dto.getLocation().equals("http://minio/bucket/file1.txt") &&
                                          dto.getStatus().equals(FileStatus.ACTIVE))
                .expectNextMatches(dto -> dto.getId().equals(2) &&
                                          dto.getUserId().equals(user1.getId()) &&
                                          dto.getName().equals("file2") &&
                                          dto.getLocation().equals("http://minio/bucket/file2.txt") &&
                                          dto.getStatus().equals(FileStatus.ARCHIVED))
                .verifyComplete();

        verify(fileRepository, times(1)).findByUserId(principal.getId());
    }
}
