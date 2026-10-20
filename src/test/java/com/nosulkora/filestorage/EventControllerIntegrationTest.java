package com.nosulkora.filestorage;

import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class EventControllerIntegrationTest extends BaseIntegrationTest {

    @Disabled
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


    @DisplayName("Отказ в доступе: Обычный USER пытается запросить историю событий.")
    @Test
    void regularUserAccessEventsForbiddenTest() {
        // 1. Попытка получить конкретное событие по ID -> 403 Forbidden
        clientWithAuth(user1Token).get()
                .uri("/api/events/1")
                .exchange()
                .expectStatus().isForbidden() // Жёсткий отказ безопасности
                .expectBody()
                .jsonPath("$.message").isEqualTo("Only MODERATOR or ADMIN can manage events");

        // 2. Попытка получить список всех событий системы -> 403 Forbidden
        clientWithAuth(user1Token).get()
                .uri("/api/events")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Only MODERATOR or ADMIN can manage events");
    }
}
