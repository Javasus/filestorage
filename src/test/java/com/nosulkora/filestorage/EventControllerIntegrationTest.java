package com.nosulkora.filestorage;

import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class EventControllerIntegrationTest extends BaseIntegrationTest {

    @DisplayName("Успешное получение событий модератором и админом по ID и списком.")
    @Test
    void moderatorAndAdminAccessEventsSuccessTest() {

        Map<String, Object> registrationRequest = Map.of(
                "username", "Ivan",
                "password", "password123",
                "role", "USER");

        UserDto createdUserDto = webTestClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationRequest)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UserDto.class)
                .returnResult()
                .getResponseBody();

        assertAll(
                "Проверяем, что обычный пользователь успешно создался: ",
                () -> assertNotNull(createdUserDto.getId()),
                () -> assertEquals("Ivan", createdUserDto.getUsername()),
                () -> assertEquals(UserStatus.ACTIVE, createdUserDto.getStatus()),
                () -> assertEquals(UserRole.USER, createdUserDto.getRole())
        );

        // Имитируем загрузку файла, которая автоматически порождает EventStatus.CREATED в БД
        ClassPathResource resource = new org.springframework.core.io.ClassPathResource("test.txt");
        LinkedMultiValueMap<String, Object> map = new org.springframework.util.LinkedMultiValueMap<>();

        HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);
        HttpEntity<ClassPathResource> fileEntity = new org.springframework.http.HttpEntity<>(resource, headers);
        map.add("file", fileEntity);

        // Делаем вызов загрузки файла под токеном созданного пользователя
        String fileUserToken = tokenProvider.createToken(
                createdUserDto.getId(),
                createdUserDto.getUsername(),
                "USER",
                "ACTIVE",
                1);

        FileDto fileDto = clientWithAuth(fileUserToken).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(map)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(fileDto);

        // Находим созданный эвент в репозитории, чтобы узнать его сгенерированный ID
        List<Event> events = eventRepository.findAll();
        assertFalse(events.isEmpty());
        Integer generatedEventId = events.get(0).getId();

        // ------------------ 1. MODERATOR ПОЛУЧАЕТ ЭВЕНТ ПО ID ------------------
        clientWithAuth(moderatorToken).get()
                .uri("/api/events/" + generatedEventId)
                .exchange()
                .expectStatus().isOk() // Ожидаем 200 OK
                .expectBody(EventDto.class)
                .value(eventDto -> assertAll(
                        "Проверка полей полученного события модератором: ",
                        () -> assertEquals(generatedEventId, eventDto.getId()),
                        () -> assertEquals(createdUserDto.getId(), eventDto.getUserId()),
                        () -> assertEquals(fileDto.getId(), eventDto.getFileId()),
                        () -> assertEquals(EventStatus.CREATED, eventDto.getStatus())
                ));

        // ------------------ 2. MODERATOR ПОЛУЧАЕТ СПИСОК ВСЕХ ЭВЕНТОВ ------------------
        clientWithAuth(moderatorToken).get()
                .uri("/api/events")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(EventDto.class)
                .value(list -> {
                    assertFalse(list.isEmpty(), "Список событий не должен быть пустым");
                    boolean hasOurEvent = list.stream().anyMatch(e -> e.getId().equals(generatedEventId));
                    assertTrue(hasOurEvent, "Модератор должен видеть созданное событие в общем списке");
                });

        // ------------------ 3. ADMIN ПОЛУЧАЕТ ЭВЕНТ ПО ID ------------------
        clientWithAuth(adminToken).get()
                .uri("/api/events/" + generatedEventId)
                .exchange()
                .expectStatus().isOk() // Ожидаем 200 OK
                .expectBody(EventDto.class)
                .value(eventDto -> assertAll(
                        "Проверка полей полученного события модератором: ",
                        () -> assertEquals(generatedEventId, eventDto.getId()),
                        () -> assertEquals(createdUserDto.getId(), eventDto.getUserId()),
                        () -> assertEquals(fileDto.getId(), eventDto.getFileId()),
                        () -> assertEquals(EventStatus.CREATED, eventDto.getStatus())
                ));

        // ------------------ 4. ADMIN ПОЛУЧАЕТ СПИСОК ВСЕХ ЭВЕНТОВ ------------------
        clientWithAuth(adminToken).get()
                .uri("/api/events")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(EventDto.class)
                .value(list -> {
                    assertFalse(list.isEmpty(), "Список событий не должен быть пустым");
                    boolean hasOurEvent = list.stream().anyMatch(e -> e.getId().equals(generatedEventId));
                    assertTrue(hasOurEvent, "Модератор должен видеть созданное событие в общем списке");
                });
    }


    @DisplayName("Интеграционный тест: Разграничение прав доступа USER к событиям (свой/чужой файл)")
    @Test
    void regularUserAccessEventsForbiddenTest() {
        // 1. USER_1 загружает свой файл (автоматический появляется событие)
        ByteArrayResource resource1 = new ByteArrayResource("content1".getBytes()) {
            @Override
            public String getFilename() {
                return "test1.txt";
            }
        };

        FileDto fileResponse1 = clientWithAuth(user1Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file", resource1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(fileResponse1);

        // 2. USER_2 загружает свой файл (автоматический появляется событие)
        ByteArrayResource resource2 = new ByteArrayResource("content2".getBytes()) {
            @Override
            public String getFilename() {
                return "test2.txt";
            }
        };

        FileDto fileResponse2 = clientWithAuth(user2Token).post()
                .uri("/api/files")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData("file", resource2))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FileDto.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(fileResponse2);

        // 3. USER_2 получает все события. Получает только одно своё событие.
        List<EventDto> user2Events = clientWithAuth(user2Token).get()
                .uri("/api/events")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(EventDto.class)
                .returnResult()
                .getResponseBody();

        assertAll(
                "Проверяем что USER_2 получил только свой EVENT: ",
                () -> assertNotNull(user2Events),
                () -> assertFalse(user2Events.isEmpty()),
                () -> assertEquals(1, user2Events.size()),
                () -> assertEquals(regularUser2.getId(), user2Events.get(0).getUserId())
        );

        Integer user2EventId = user2Events.get(0).getId();

        // 4. USER_1 пытается получить чужой EVENT по ID -> 403 Forbidden
        clientWithAuth(user1Token).get()
                .uri("/api/events/" + user2EventId)
                .exchange()
                .expectStatus().isForbidden() // Жёсткий отказ безопасности
                .expectBody()
                .jsonPath("$.message").isEqualTo("You do not have permission to view this event");

        // 5. USER_1 пытается получить список всех EVENT -> получает только свой.
        List<EventDto> user1Events = clientWithAuth(user1Token).get()
                .uri("/api/events")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(EventDto.class)
                .returnResult()
                .getResponseBody();

        assertAll(
                "Проверяем что USER_1 получил только свой EVENT: ",
                () -> assertNotNull(user1Events),
                () -> assertFalse(user1Events.isEmpty()),
                () -> assertEquals(1, user1Events.size()),
                () -> assertEquals(regularUser1.getId(), user1Events.get(0).getUserId())
        );
    }
}
