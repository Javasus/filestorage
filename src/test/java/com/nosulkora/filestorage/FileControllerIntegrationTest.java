package com.nosulkora.filestorage;

import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.FileStatus;
import io.minio.StatObjectArgs;
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

    @DisplayName("Сквозной CRUD-сценарий работы с файлом для роли USER.")
    @Test
    void regularUserUploadFileSuccessfulTest() {
        // ------------------ 1. ЗАГРУЗКА ФАЙЛА. ------------------
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ClassPathResource(FILENAME))
                .filename(FILENAME)
                .contentType(MediaType.TEXT_PLAIN);

        MultiValueMap<String, HttpEntity<?>> multipartBody = bodyBuilder.build();

        // Выполним Post запрос на загрузку файла с JWT-токеном обычного пользователя.
        FileDto responseFileDto = clientWithAuth(user1Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multipartBody)
                .exchange()
                .expectStatus().isCreated() // ожидаем 201 Created
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        // Проверки после загрузки файла:
        // 1.1 Проверка существования физического файла в Minio
        String fileLocation = responseFileDto.getLocation();
        String objectName = fileLocation.substring(fileLocation.lastIndexOf("/"));
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
                () -> assertEquals(responseFileDto.getName(), FILENAME),
                () -> assertThat(responseFileDto.getLocation()).contains("user-files/").contains(FILENAME),
                // Проверяем, что файл записался в БД.
                () -> assertThat(fileRepository.findById(responseFileDto.getId())).isPresent(),
                () -> assertTrue(fileExistsInS3, "Проверка физического сохранения в S3"),
                () -> assertTrue(isUploadEventSaved, "Проверка лога CREATED в БД")
        );

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

        // 3. ------------------ ИЗМЕНЕНИЕ ИМЕНИ ФАЙЛА. ------------------
        String updateName = "update_" + FILENAME;
        FileDto updateRequestDto = new FileDto();
        updateRequestDto.setName(updateName);
        updateRequestDto.setLocation(responseFileDto.getLocation());

        clientWithAuth(user1Token).put()
                .uri("/api/files/" + fileId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateRequestDto)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FileDto.class)
                .value(updatedFile -> assertThat(updatedFile.getName()).isEqualTo(updateName));

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

        // 4. ------------------ УДАЛЕНИЕ ФАЙЛА ПО ID. ------------------
        clientWithAuth(user1Token).delete()
                .uri("/api/files/" + fileId)
                .exchange()
                .expectStatus().isNoContent();

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
                               e.getStatus() == EventStatus.CREATED);

        final boolean fileStillInS3 = existsInMinioAfterDelete;
        final boolean isDeleteEventSaved = hasDeleteEvent;


        assertAll(
                "Проверки при удалении файла: ",
                () -> assertEquals(deletedFile.getStatus().name(), FileStatus.ARCHIVED.name()),
                () -> assertTrue(fileStillInS3, "Физический файл не удален из MinIO"),
                () -> assertTrue(isDeleteEventSaved, "Лог DELETED успешно сохранен в БД")
        );
    }

    @DisplayName("Загрузка файла пользователем с ролью: ADMIN.")
    @Test
    void adminUploadFileSuccessfulTest() {
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("file", new ClassPathResource(FILENAME))
                .filename(FILENAME)
                .contentType(MediaType.TEXT_PLAIN);

        MultiValueMap<String, HttpEntity<?>> multipartBody = bodyBuilder.build();

        // Выполним Post запрос на загрузку файла с JWT-токеном админа.
        FileDto responseFileDto = clientWithAuth(adminToken).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(multipartBody)
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

    // TODO
    // 1. Написать тест где в сервис обращается пользователь у которого status = BLOCKED.

}
