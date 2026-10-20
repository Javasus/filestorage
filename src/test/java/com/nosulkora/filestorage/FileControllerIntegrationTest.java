package com.nosulkora.filestorage;

import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.FileStatus;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * тестовый файл test.txt в src/test/resources/
 */
public class FileControllerIntegrationTest extends BaseIntegrationTest {

    private final static String FILENAME = "test.txt";

    @DisplayName("""
            Сквозной CRUD-сценарий работы со своим файлом для роли USER.
            1. USER_1 успешно загружает файл.
            2. USER_1 успешно читает файл.
            3. USER_1 успешно читает все файлы. Получает список со своим файлом.
            4. USER_1 успешно изменяет файл.
            5. USER_1 успешно удаляет файл.""")
    @Test
    void regularUserUploadFileSuccessfulTest() {
        // 1. ------------------ ЗАГРУЗКА ФАЙЛА. ------------------

        // Выполним Post запрос на загрузку файла с JWT-токеном обычного пользователя.
        FileDto responseFileDto = clientWithAuth(user1Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(FILENAME))
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        String fileLocation = responseFileDto.getLocation();
        String objectName = fileLocation.substring(fileLocation.lastIndexOf("/"));
        assertNotNull(responseFileDto);
        checkSuccessUploadFile(responseFileDto, objectName, FILENAME);

        Integer fileId = responseFileDto.getId();

        // 2. ------------------ ПОИСК ФАЙЛА ПО ID. ------------------
        clientWithAuth(user1Token).get()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileDto.class)
                .value(fetchedFile -> assertAll(
                        () -> assertThat(fetchedFile.getName()).isEqualTo(FILENAME),
                        () -> assertThat(fetchedFile.getUserId()).isEqualTo(regularUser1.getId())
                ));

        // 3. ------------------ USER_1 ПОЛУЧАЕТ ВСЕ ФАЙЛЫ. ------------------

        clientWithAuth(user1Token).get()
                .uri("/api/files")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FileDto.class)
                .value(list -> {

                    FileDto fileDto = list.get(0);
                    assertAll(
                            () -> assertEquals(1, list.size()),
                            () -> assertEquals(FILENAME, fileDto.getName()),
                            () -> assertEquals(regularUser1.getId(), fileDto.getUserId())
                    );
                });

        // 4. ------------------ ИЗМЕНЕНИЕ ИМЕНИ ФАЙЛА. ------------------
        String updateName = "update_" + FILENAME;
        FileDto updateRequestDto = new FileDto();
        updateRequestDto.setName(updateName);

        clientWithAuth(user1Token).put()
                .uri("/api/files/" + fileId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequestDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileDto.class)
                .value(updatedFile -> assertThat(updatedFile.getName()).isEqualTo(updateName));

        checkSuccessUpdateFile(fileId, updateName);

        // 5. ------------------ УДАЛЕНИЕ ФАЙЛА ПО ID. ------------------
        clientWithAuth(user1Token).delete()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isNoContent();

        checkSuccessDeleteFile(fileId, objectName, responseFileDto);
    }

    @DisplayName("""
            Работа MODERATOR с файлами пользователей.
            1. USER_1 успешно загружает файл.
            2. MODERATOR успешно читает чужой файл.
            3. MODERATOR успешно изменяет чужой файл.
            4. MODERATOR успешно удаляет чужой файл.""")
    @Test
    void managementForeignFileAsModeratorSuccessTest() {
        // 1. ------------------ ЗАГРУЗКА ФАЙЛА. ------------------

        // Выполним Post запрос на загрузку файла с JWT-токеном обычного пользователя.
        FileDto responseFileDto = clientWithAuth(user1Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(FILENAME))
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        String fileLocation = responseFileDto.getLocation();
        String objectName = fileLocation.substring(fileLocation.lastIndexOf("/"));
        assertNotNull(responseFileDto);
        checkSuccessUploadFile(responseFileDto, objectName, FILENAME);

        Integer fileId = responseFileDto.getId();

        // 2. ------------------ ПОИСК ФАЙЛА ПО ID. ------------------
        clientWithAuth(moderatorToken).get()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileDto.class)
                .value(fetchedFile -> assertAll(
                        () -> assertThat(fetchedFile.getName()).isEqualTo(FILENAME),
                        () -> assertThat(fetchedFile.getUserId()).isEqualTo(regularUser1.getId())
                ));

        // 3. ------------------ ИЗМЕНЕНИЕ ИМЕНИ ФАЙЛА. ------------------
        String updateName = "update_" + FILENAME;
        FileDto updateRequestDto = new FileDto();
        updateRequestDto.setName(updateName);

        clientWithAuth(moderatorToken).put()
                .uri("/api/files/" + fileId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequestDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileDto.class)
                .value(updatedFile -> assertThat(updatedFile.getName()).isEqualTo(updateName));

        checkSuccessUpdateFile(fileId, updateName);

        // 4. ------------------ УДАЛЕНИЕ ФАЙЛА ПО ID. ------------------
        clientWithAuth(moderatorToken).delete()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isNoContent();

        checkSuccessDeleteFile(fileId, objectName, responseFileDto);
    }

    @DisplayName("""
            Работа ADMIN с файлами пользователей.
            1. USER_1 успешно загружает файл.
            2. ADMIN успешно читает чужой файл.
            3. ADMIN успешно изменяет чужой файл.
            4. ADMIN успешно удаляет чужой файл.""")
    @Test
    void managementForeignFileAsAdminSuccessTest() {
        // 1. ------------------ ЗАГРУЗКА ФАЙЛА. ------------------

        // Выполним Post запрос на загрузку файла с JWT-токеном обычного пользователя.
        FileDto responseFileDto = clientWithAuth(user1Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(FILENAME))
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        String fileLocation = responseFileDto.getLocation();
        String objectName = fileLocation.substring(fileLocation.lastIndexOf("/"));
        assertNotNull(responseFileDto);
        checkSuccessUploadFile(responseFileDto, objectName, FILENAME);

        Integer fileId = responseFileDto.getId();

        // 2. ------------------ ПОИСК ФАЙЛА ПО ID. ------------------

        clientWithAuth(adminToken).get()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileDto.class)
                .value(fetchedFile -> assertAll(
                        () -> assertThat(fetchedFile.getName()).isEqualTo(FILENAME),
                        () -> assertThat(fetchedFile.getUserId()).isEqualTo(regularUser1.getId())
                ));

        // 3. ------------------ ИЗМЕНЕНИЕ ИМЕНИ ФАЙЛА. ------------------

        String updateName = "update_" + FILENAME;
        FileDto updateRequestDto = new FileDto();
        updateRequestDto.setName(updateName);

        clientWithAuth(adminToken).put()
                .uri("/api/files/" + fileId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequestDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileDto.class)
                .value(updatedFile -> assertThat(updatedFile.getName()).isEqualTo(updateName));

        checkSuccessUpdateFile(fileId, updateName);

        // 4. ------------------ УДАЛЕНИЕ ФАЙЛА ПО ID. ------------------

        clientWithAuth(adminToken).delete()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isNoContent();

        checkSuccessDeleteFile(fileId, objectName, responseFileDto);
    }

    @DisplayName("""
            Проверка не санкционированного доступа пользователя (USER) к чужим файлам.
            1. USER_1 успешно загружает файл.
            2. USER_2 пытается прочитать чужой файл - Forbidden.
            3. USER_2 пытается изменить чужой файл - Forbidden.
            4. USER_2 пытается удалить чужой файл - Forbidden.
            5. USER_2 пытается получить все файлы - Не получает файл USER_1.
            6. Проверяем что файл который загрузил USER_1 на месте и не изменился.""")
    @Test
    void managementForbiddenFileAsUserAccessDeniedTest() {
        // 1. ------------------ ЗАГРУЗКА ФАЙЛА. ------------------

        // Выполним Post запрос на загрузку файла с JWT-токеном обычного пользователя.
        FileDto responseFileDto = clientWithAuth(user1Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(FILENAME))
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        String fileLocation = responseFileDto.getLocation();
        String objectName = fileLocation.substring(fileLocation.lastIndexOf("/"));
        assertNotNull(responseFileDto);
        checkSuccessUploadFile(responseFileDto, objectName, FILENAME);

        Integer fileId = responseFileDto.getId();

        // 2. ------------------ ПОИСК ФАЙЛА ПО ID ДРУГИМ ПОЛЬЗОВАТЕЛЕМ. ------------------

        clientWithAuth(user2Token).get()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("You do not have permission to work with this file.")
                .jsonPath("$.error").isEqualTo("Forbidden");

        // 3. ------------------ ИЗМЕНЕНИЕ ИМЕНИ ФАЙЛА. ------------------
        String updateName = "update_" + FILENAME;
        FileDto updateRequestDto = new FileDto();
        updateRequestDto.setName(updateName);

        clientWithAuth(user2Token).put()
                .uri("/api/files/" + fileId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequestDto)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("You do not have permission to work with this file.")
                .jsonPath("$.error").isEqualTo("Forbidden");

        List<Event> eventsAfterUpdate = eventRepository.findAll();
        boolean hasUpdatedEvent = eventsAfterUpdate.stream()
                .anyMatch(e -> e.getFile().getId().equals(fileId) &&
                               e.getStatus() == EventStatus.UPDATED);

        assertAll(
                "Проверки при изменении файла: ",
                () -> assertEquals(fileRepository.findById(fileId).get().getName(), FILENAME,
                        "Проверим, что имя файла не изменилось в БД"),
                () -> assertFalse(hasUpdatedEvent, "Проверка отсутствия создания лога UPDATED в БД")
        );

        // 4. ------------------ УДАЛЕНИЕ ФАЙЛА ПО ID. ------------------

        clientWithAuth(user2Token).delete()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("You do not have permission to work with this file.")
                .jsonPath("$.error").isEqualTo("Forbidden");

        // 5. ------------------ ПОЛУЧЕНИЕ ВСЕХ ФАЙЛОВ. ------------------

        clientWithAuth(user2Token).get()
                .uri("/api/files")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FileDto.class)
                .value(list -> {
                    assertTrue(list.isEmpty());
                });

        // 6. ------------------ ПРОВЕРЯЕМ, ЧТО ФАЙЛ НЕ УДАЛИЛСЯ И СТАТУС НЕ ИЗМЕНИЛСЯ. ------------------

        File deletedFile = fileRepository.findById(fileId).orElseThrow(
                () -> new AssertionError("File record not found in DB!"));

        boolean existsInMinioAfterDelete = false;
        try {
            testMinioClient.statObject(StatObjectArgs.builder()
                    .bucket("user-files")
                    .object(objectName)
                    .build());
            existsInMinioAfterDelete = true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Event> eventsAfterDelete = eventRepository.findAll();
        boolean hasDeleteEvent = eventsAfterDelete.stream()
                .anyMatch(e -> e.getFile().getId().equals(responseFileDto.getId()) &&
                               e.getStatus() == EventStatus.DELETED);

        final boolean fileStillInS3 = existsInMinioAfterDelete;
        final boolean isDeleteEventSaved = hasDeleteEvent;

        assertAll(
                "Проверки при удалении файла: ",
                () -> assertEquals(
                        deletedFile.getStatus().name(),
                        FileStatus.ACTIVE.name(),
                        "Статус файла не поменялся на ARCHIVED"),
                () -> assertTrue(fileStillInS3, "Физический файл не удален из MinIO"),
                () -> assertFalse(isDeleteEventSaved, "Лог DELETED отсутствует в БД")
        );
    }

    @DisplayName("""
            Получение списка файлом пользователей с разными ролями.
            1. USER_1 успешно загружает файл.
            2. USER_2 успешно загружает файл.
            3. USER_1 получает список всех файлов. В списке только его файл.
            4. USER_2 получает список всех файлов. В списке только его файл.
            5. ADMIN получает список всех файлов. В списке оба файла.
            6. MODERATOR получает список всех файлов. В списке оба файла.""")
    @Test
    void getAllFilesTest() {
        // ------------------ 1. ЗАГРУЗКА ФАЙЛА-1. ------------------

        // Выполним Post запрос на загрузку файла с JWT-токеном обычного пользователя.
        FileDto responseFileDto1 = clientWithAuth(user1Token)
                .post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(FILENAME))
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(responseFileDto1);
        String fileLocation1 = responseFileDto1.getLocation();
        String fileName1 = fileLocation1.substring(fileLocation1.lastIndexOf("/"));
        checkSuccessUploadFile(responseFileDto1, fileName1, FILENAME);

        // ------------------ 2. ЗАГРУЗКА ФАЙЛА-2. ------------------

        // Выполним Post запрос на загрузку файла с JWT-токеном обычного пользователя.
        String filename2 = "test2.txt";
        FileDto responseFileDto2 = clientWithAuth(user2Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(filename2))
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        String fileLocation2 = responseFileDto2.getLocation();
        String objectName2 = fileLocation2.substring(fileLocation2.lastIndexOf("/"));
        assertNotNull(responseFileDto2);
        checkSuccessUploadFile(responseFileDto2, objectName2, filename2);

        // ------------------ 3. USER_1 ПОЛУЧАЕТ ВСЕ ФАЙЛЫ. ------------------

        clientWithAuth(user1Token).get()
                .uri("/api/files")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FileDto.class)
                .value(list -> {

                    FileDto fileDto = list.get(0);
                    assertAll(
                            () -> assertEquals(1, list.size()),
                            () -> assertEquals(FILENAME, fileDto.getName()),
                            () -> assertEquals(regularUser1.getId(), fileDto.getUserId())
                    );
                });

        // ------------------ 4. USER_2 ПОЛУЧАЕТ ВСЕ ФАЙЛЫ. ------------------

        clientWithAuth(user2Token).get()
                .uri("/api/files")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FileDto.class)
                .value(list -> {

                    FileDto fileDto = list.get(0);
                    assertAll(
                            () -> assertEquals(1, list.size()),
                            () -> assertEquals(filename2, fileDto.getName()),
                            () -> assertEquals(regularUser2.getId(), fileDto.getUserId())
                    );
                });

        // ------------------ 5. ADMIN ПОЛУЧАЕТ ВСЕ ФАЙЛЫ. ------------------

        clientWithAuth(adminToken).get()
                .uri("/api/files")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FileDto.class)
                .value(list -> {

                    assertEquals(2, list.size(), "Админ должен видеть ровно 2 файла");

                    FileDto fileDto1 = list.stream()
                            .filter(fileDto -> fileDto.getUserId().equals(regularUser1.getId()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Файл пользователя USER1 не найден в списке"));

                    FileDto fileDto2 = list.stream()
                            .filter(fileDto -> fileDto.getUserId().equals(regularUser2.getId()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Файл пользователя USER2 не найден в списке"));

                    assertAll(
                            "Комплексная проверка метаданных файлов для ADMIN: ",
                            // Проверки для первого файла
                            () -> assertEquals(FILENAME, fileDto1.getName()),
                            () -> assertNotNull(fileDto1.getLocation(), "Локация файла USER1 не должна быть null"),
                            // Проверки для второго файла
                            () -> assertEquals(filename2, fileDto2.getName()),
                            () -> assertNotNull(fileDto2.getLocation(), "Локация файла USER2 не должна быть null")
                    );
                });

        // ------------------ 6. MODERATOR ПОЛУЧАЕТ ВСЕ ФАЙЛЫ. ------------------

        clientWithAuth(moderatorToken).get()
                .uri("/api/files")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FileDto.class)
                .value(list -> {

                    assertEquals(2, list.size(), "Модератор должен видеть ровно 2 файла");

                    FileDto fileDto1 = list.stream()
                            .filter(fileDto -> fileDto.getUserId().equals(regularUser1.getId()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Файл пользователя USER1 не найден в списке"));

                    FileDto fileDto2 = list.stream()
                            .filter(fileDto -> fileDto.getUserId().equals(regularUser2.getId()))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("Файл пользователя USER2 не найден в списке"));

                    assertAll(
                            "Комплексная проверка метаданных файлов для MODERATOR: ",
                            // Проверки для первого файла
                            () -> assertEquals(FILENAME, fileDto1.getName()),
                            () -> assertNotNull(fileDto1.getLocation(), "Локация файла USER1 не должна быть null"),
                            // Проверки для второго файла
                            () -> assertEquals(filename2, fileDto2.getName()),
                            () -> assertNotNull(fileDto2.getLocation(), "Локация файла USER2 не должна быть null")
                    );
                });
    }

    @DisplayName("""
             Проверка корректной работы обработчика исключений.
             1. USER_1 загружает текстовое поле вместо файла -> ожидаем 400.
             2. USER_1 загружает файл с пустым именем "" -> ожидаем 400.
             3. USER_1 обновляет не существующий файл -> ожидаем 404.
             4. USER_1 удаляет не существующий файл -> ожидаем 404.
             5. USER_1 делает запрос к несуществующему файлу -> ожидаем 404.
            """)
    @Test
    void validationTest() {
        // ------------------ 1. ЗАГРУЗКА ТЕКСТОВОГО ПОЛЯ ВМЕСТО ФАЙЛА. ------------------

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", "  ", MediaType.TEXT_PLAIN);
        MultiValueMap<String, HttpEntity<?>> multiValueMap = bodyBuilder.build();

        clientWithAuth(user1Token)
                .post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multiValueMap)
                .exchange()
                .expectStatus().isBadRequest() // ожидаем 400
                .expectBody()
                .jsonPath("$.message").isEqualTo("File name cannot be empty")
                .jsonPath("$.error").isEqualTo("Bad Request");

        // ------------------ 2. ЗАГРУЗКА ФАЙЛА С ПУСТЫМ ИМЕНЕМ " ". ------------------

        clientWithAuth(user1Token)
                .post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(" "))
                .exchange()
                .expectStatus().isBadRequest() // ожидаем 400
                .expectBody()
                .jsonPath("$.message").isEqualTo("File name cannot be empty")
                .jsonPath("$.error").isEqualTo("Bad Request");

        // ------------------ 3. ИЗМЕНЕНИЕ ИМЕНИ НЕ СУЩЕСТВУЮЩЕГО ФАЙЛА. ------------------

        String updateName = "update_" + FILENAME;
        FileDto updateRequestDto = new FileDto();
        updateRequestDto.setName(updateName);
        updateRequestDto.setId(500);

        clientWithAuth(user1Token).put()
                .uri("/api/files/" + updateRequestDto.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequestDto)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.message").isEqualTo(
                        "File not found in DB with id: " + updateRequestDto.getId())
                .jsonPath("$.error").isEqualTo("Not Found");

        // ------------------ 4. УДАЛЕНИЕ НЕ СУЩЕСТВУЮЩЕГО ФАЙЛА ПО ID. ------------------

        clientWithAuth(user1Token).delete()
                .uri("/api/files/" + updateRequestDto.getId())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.message").isEqualTo(
                        "File not found in DB with id: " + updateRequestDto.getId())
                .jsonPath("$.error").isEqualTo("Not Found");

        // ------------------ 5. ПОИСК НЕ СУЩЕСТВУЮЩЕГО ФАЙЛА ПО ID. ------------------

        clientWithAuth(user1Token).get()
                .uri("/api/files/" + updateRequestDto.getId())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.message").isEqualTo(
                        "File not found in DB with id: " + updateRequestDto.getId())
                .jsonPath("$.error").isEqualTo("Not Found");
    }

    @DisplayName("Загрузка файла пользователем с ролью: ADMIN.")
    @Test
    void adminUploadFileSuccessfulTest() {

        // Выполним Post запрос на загрузку файла с JWT-токеном админа.
        FileDto responseFileDto = clientWithAuth(adminToken).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(getMultipartBody(FILENAME))
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        // Проверяем полученный response
        assertThat(responseFileDto).isNotNull();
        assertThat(responseFileDto.getId()).isNotNull();
        assertThat(responseFileDto.getName()).isEqualTo(FILENAME);
        assertThat(responseFileDto.getLocation()).contains("user-files/").contains(FILENAME);

        // Проверяем, что файл записался в БД.
        boolean existInDb = fileRepository.findById(responseFileDto.getId()).isPresent();
        assertThat(existInDb).isTrue();
    }

    // ------------------------- Вспомогательные методы -------------------------

    private MultiValueMap<String, HttpEntity<?>> getMultipartBody(String filename) {

        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        MultipartBodyBuilder.PartBuilder partBuilder = bodyBuilder
                .part("file", new ClassPathResource(FILENAME))
                .contentType(MediaType.TEXT_PLAIN);

        if (filename != null) {
            partBuilder.filename(filename);
        }

        return bodyBuilder.build();
    }

    /**
     * Совершает проверки после успешной загрузки файла.
     * <br> 1. Проверка существования физического файла в Minio.
     * <br> 2. Проверка автоматического создания Event в БД.
     * <br> 3. Проверка записи о загрузке файла в БД.
     */
    private void checkSuccessUploadFile(
            FileDto responseFileDto,
            String objectName,
            String filename
    ) {
        // Проверки после загрузки файла:
        // 1.1 Проверка существования физического файла в Minio

        boolean existsInMinioAfterUpload = false;
        try {
            testMinioClient.statObject(StatObjectArgs.builder()
                    .bucket("user-files")
                    .object(objectName)
                    .build());
            existsInMinioAfterUpload = true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 1.2 Проверка автоматического создания Event в БД
        List<Event> eventsAfterUpload = eventRepository.findAll();
        boolean hasCreatedEvent = eventsAfterUpload.stream()
                .anyMatch(e -> e.getFile().getId().equals(responseFileDto.getId()) &&
                               e.getStatus() == EventStatus.CREATED);

        final boolean fileExistsInS3 = existsInMinioAfterUpload;
        final boolean isUploadEventSaved = hasCreatedEvent;

        // 1.3 Проверка записи о загрузке файла в БД
        assertAll(
                "Проверки при загрузке файла: ",
                // Проверяем полученный response
                () -> assertNotNull(responseFileDto),
                () -> assertNotNull(responseFileDto.getId()),
                () -> assertEquals(filename, responseFileDto.getName(), "filename"),
                () -> assertThat(responseFileDto.getLocation()).contains("user-files/").contains(filename),
                // Проверяем, что файл записался в БД.
                () -> assertThat(fileRepository.findById(responseFileDto.getId())).isPresent(),
                () -> assertTrue(fileExistsInS3, "Проверка физического сохранения в S3"),
                () -> assertTrue(isUploadEventSaved, "Проверка лога CREATED в БД")
        );
    }

    /**
     * Совершает проверки при успешном изменении файла.
     * <br> 1. Проверка изменение имени файла в БД.
     * <br> 2. Проверка создания Event со статусом UPDATE.
     */
    private void checkSuccessUpdateFile(Integer fileId, String updateName) {
        List<Event> eventsAfterUpdate = eventRepository.findAll();
        boolean hasUpdatedEvent = eventsAfterUpdate.stream()
                .anyMatch(e -> e.getFile().getId().equals(fileId) &&
                               e.getStatus() == EventStatus.UPDATED);

        assertAll(
                "Проверки при изменении файла: ",
                () -> assertEquals(fileRepository.findById(fileId).get().getName(), updateName,
                        "Проверим, что имя файла обновилось в БД"),
                () -> assertTrue(hasUpdatedEvent, "Проверка автоматического создания лога UPDATED в БД")
        );
    }

    /**
     * Совершает проверки при успешном удалении файла.
     * <br> 1. Проверка существования файла в БД со статусом ARCHIVED.
     * <br> 2. Проверка существования файла в MinIO по указанному пути.
     * <br> 3. Проверка создания Event со статусом DELETE.
     */
    private void checkSuccessDeleteFile(Integer fileId, String objectName, FileDto responseFileDto) {
        File deletedFile = fileRepository.findById(fileId).orElseThrow(
                () -> new AssertionError("File record not found in DB!"));

        boolean existsInMinioAfterDelete = false;
        try {
            testMinioClient.statObject(StatObjectArgs.builder()
                    .bucket("user-files")
                    .object(objectName)
                    .build());
            existsInMinioAfterDelete = true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Event> eventsAfterDelete = eventRepository.findAll();
        boolean hasDeleteEvent = eventsAfterDelete.stream()
                .anyMatch(e -> e.getFile().getId().equals(responseFileDto.getId()) &&
                               e.getStatus() == EventStatus.DELETED);

        final boolean fileStillInS3 = existsInMinioAfterDelete;
        final boolean isDeleteEventSaved = hasDeleteEvent;

        assertAll(
                "Проверки при удалении файла: ",
                () -> assertEquals(deletedFile.getStatus().name(), FileStatus.ARCHIVED.name()),
                () -> assertTrue(fileStillInS3, "Физический файл не удален из MinIO"),
                () -> assertTrue(isDeleteEventSaved, "Лог DELETED успешно сохранен в БД")
        );
    }
}
