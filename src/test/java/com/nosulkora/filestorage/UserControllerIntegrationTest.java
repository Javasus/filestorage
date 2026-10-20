package com.nosulkora.filestorage;

import com.nosulkora.filestorage.mapper.UserMapper;
import com.nosulkora.filestorage.model.dto.AuthRequestDto;
import com.nosulkora.filestorage.model.dto.AuthResponseDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

public class UserControllerIntegrationTest extends BaseIntegrationTest {

    @DisplayName("""
            Полный CRUD-сценарий работы админа с пользователем и проверка Soft Delete.
            1. Регистрация пользователя.
            2. ADMIN запрашивает пользователя по ID.
            3. ADMIN запрашивает всех пользователей.
            4. ADMIN удаляет пользователя.
            5. Проверяем изменение статуса на BLOCKED.""")
    @Test
    void adminCrudAndSoftDeleteSuccessTest() {
        // 1. ------------------ РЕГИСТРАЦИЯ ПОЛЬЗОВАТЕЛЯ ЧЕРЕЗ AUTH API. ------------------

        String username = "Ivan";

        Map<String, Object> registrationRequest = Map.of(
                "username", username,
                "password", "password",
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
                () -> assertEquals(username, createdUserDto.getUsername()),
                () -> assertEquals(UserStatus.ACTIVE, createdUserDto.getStatus()),
                () -> assertEquals(UserRole.USER, createdUserDto.getRole())
        );

        Integer userId = createdUserDto.getId();

        // 2. ------------------ ADMIN ЗАПРАШИВАЕТ ПОЛЬЗОВАТЕЛЯ ПО ID. ------------------

        clientWithAuth(adminToken).get()
                .uri("/api/users/" + userId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserDto.class)
                .value(userDto -> {
                    assertThat(createdUserDto)
                            .usingRecursiveComparison()
                            .isEqualTo(userDto);
                });

        // 3. ------------------ ADMIN ЗАПРАШИВАЕТ ВСЕХ ПОЛЬЗОВАТЕЛЕЙ. ------------------

        clientWithAuth(adminToken).get()
                .uri("/api/users")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserDto.class)
                .value(list -> {
                    UserDto userDto = list.stream()
                            .filter(user -> user.getId().equals(userId))
                            .findFirst()
                            .orElseThrow(() ->
                                    new AssertionError(
                                            "В списке пользователей отсутствует пользователь с ID: "
                                            + userId));

                    assertThat(createdUserDto)
                            .usingRecursiveComparison()
                            .isEqualTo(userDto);
                });

        // 4. ------------------ ADMIN УДАЛЯЕТ ПОЛЬЗОВАТЕЛЯ. ------------------

        clientWithAuth(adminToken).delete()
                .uri("/api/users/" + userId)
                .exchange()
                .expectStatus().isNoContent();

        // 5. ------------------ ПРОВЕРЯЕМ ИЗМЕНЕНИЕ СТАТУСА НА BLOCKED. ------------------

        clientWithAuth(adminToken).get()
                .uri("/api/users/" + userId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserDto.class)
                .value(userDto -> {
                    assertAll(
                            () -> assertEquals(userId, userDto.getId()),
                            () -> assertEquals(username, userDto.getUsername()),
                            () -> assertEquals(UserStatus.BLOCKED, userDto.getStatus())
                    );
                });
    }

    @DisplayName("""
            Полный CRUD-сценарий работы админа с пользователем и проверка Soft Delete.
            1. Регистрация пользователя.
            2. ADMIN удаляет пользователя по ID.
            3. USER запрашивает свой профиль по своему ID.
            4. USER логиниться.
            5. USER меняет свой статус на ACTIVE.""")
    @Test
    void adminDeleteUserSuccessTest() {
        // 1. ------------------ РЕГИСТРАЦИЯ ПОЛЬЗОВАТЕЛЯ ЧЕРЕЗ AUTH API. ------------------

        String username = "Ivan";
        String password = "password_123";

        Map<String, Object> registrationRequest = Map.of(
                "username", username,
                "password", password);

        UserDto createdUserDto = webTestClient.post()
                .uri("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registrationRequest)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UserDto.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(createdUserDto);
        Integer userId = createdUserDto.getId();

        AuthRequestDto loginRequest = new AuthRequestDto();
        loginRequest.setUsername(username);
        loginRequest.setPassword(password);


        AuthResponseDto loginResponse = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(loginRequest)
                .exchange()
                .expectStatus().isOk() // Ожидаем 200 OK
                .expectBody(AuthResponseDto.class)
                .returnResult()
                .getResponseBody();


        assertNotNull(loginResponse);
        String liveUserToken = loginResponse.getToken();
        assertNotNull(liveUserToken);

        // 2. ------------------ ADMIN УДАЛЯЕТ ПОЛЬЗОВАТЕЛЯ ПО ID. ------------------

        clientWithAuth(adminToken).delete()
                .uri("/api/users/" + userId)
                .exchange()
                .expectStatus().isNoContent();

        // 3. ------------------ USER ЗАПРАШИВАЕТ СВОЙ ПРОФИЛЬ ПО ID. ------------------

        clientWithAuth(liveUserToken).get()
                .uri("/api/users/" + userId)
                .exchange()
                .expectStatus().isForbidden();

        // 4. ------------------ USER ЛОГИНЕТСЯ. ------------------

        clientWithAuth(liveUserToken).post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(loginRequest)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Your account is blocked. Please contact support.");

        // 5. ------------------ USER МЕНЯЕТ СВОЙ СТАТУС НА ACTIVE. ------------------

        Map<String, Object> updateStatusRequest = Map.of(
                "id", userId,
                "username", username,
                "status", "ACTIVE");

        clientWithAuth(liveUserToken).put()
                .uri("/api/users/" + userId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateStatusRequest)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Access Denied")
                .jsonPath("$.message").isEqualTo("Your account is blocked. Please contact support.");
    }

    @DisplayName("""
            Изоляция данных на чтение и обновление.
             1. USER запрашивает данные своего профиля.
             2. USER запрашивает данные чужого профиля.
             3. USER обновляет данные чужого профиля.
             4. USER удаляет другого пользователя.""")
    @Test
    void userGetAndUpdateForeignProfileAccessDeniedTest() {

        // 1. ------------------ USER ЗАПРАШИВАЕТ ДАННЫЕ СВОЕГО ПРОФИЛЯ. ------------------

        clientWithAuth(user1Token).get()
                .uri("/api/users/" + regularUser1.getId())
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserDto.class)
                .value(userDto -> {
                    assertAll(
                            () -> assertEquals(regularUser1.getId(), userDto.getId()),
                            () -> assertEquals(regularUser1.getUsername(), userDto.getUsername()),
                            () -> assertEquals(regularUser1.getStatus(), userDto.getStatus())
                    );
                });

        // 2. ------------------ USER ЗАПРАШИВАЕТ ДАННЫЕ ЧУЖОГО ПРОФИЛЯ. ------------------

        clientWithAuth(user1Token).get()
                .uri("/api/users/" + regularUser2.getId())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("You do not have permission to perform this action.");

        // 3. ------------------ USER ОБНОВЛЯЕТ ДАННЫЕ ЧУЖОГО ПРОФИЛЯ. ------------------

        Map<String, Object> updateStatusRequest = Map.of(
                "username", "IVAN ROBERTOVICH",
                "status", "BLOCKED");

        clientWithAuth(user1Token).put()
                .uri("/api/users/" + regularUser2.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateStatusRequest)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("You do not have permission to perform this action.");

        User user2 = userRepository.findById(regularUser2.getId()).stream().findFirst().orElseThrow(() ->
                new AssertionError(
                        "В списке пользователей отсутствует пользователь с ID: "
                        + regularUser2.getId()));
        assertAll(
                () -> assertEquals("test_user2", user2.getUsername()),
                () -> assertEquals(UserStatus.ACTIVE, user2.getStatus())
        );

        // 4. ------------------ USER УДАЛЯЕТ ДРУГОГО ПОЛЬЗОВАТЕЛЯ. ------------------

        clientWithAuth(user1Token).delete()
                .uri("/api/users/" + regularUser2.getId())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("You do not have permission to perform this action.");

        User user = userRepository.findById(regularUser2.getId()).stream().findFirst().orElseThrow(() ->
                new AssertionError(
                        "В списке пользователей отсутствует пользователь с ID: "
                        + regularUser2.getId()));
    }

    @DisplayName("""
            Защита от несанкционированного изменения ролей.
             1. USER регистрируется с ролью ADMIN.
             2. USER обновляет свой статус на ADMIN.
             3. USER регистрируется с ролью MODERATOR.
             4. USER обновляет свой статус на MODERATOR.
             5. ADMIN обновляет статус USER на ADMIN.
             6. ADMIN обновляет статус USER на MODERATOR.
             """)
    @Test
    void changeRoleSuccessTest() {

        // 1. ------------------ USER РЕГИСТРИРУЕТСЯ С РОЛЬЮ ADMIN. ------------------

        String username = "Ivan Ivanov";

        Map<String, Object> registrationRequest = Map.of(
                "username", username,
                "password", "password",
                "role", "ADMIN"
        ); // Проверим, что пользователь не может сам себе присвоить роль ADMIN

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
                () -> assertEquals(username, createdUserDto.getUsername()),
                () -> assertEquals(UserStatus.ACTIVE, createdUserDto.getStatus()),
                () -> assertEquals(UserRole.USER, createdUserDto.getRole()));

        // 2. ------------------ USER МЕНЯЕТ СВОЮ РОЛЬ НА ADMIN. ------------------

        Map<String, Object> updateStatusRequest = Map.of(
                "username", username,
                "role", "ADMIN");

        clientWithAuth(user1Token).put()
                .uri("/api/users/" + regularUser1.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateStatusRequest)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("You do not have permission to perform this action.");

        User user = userRepository.findById(regularUser1.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "В списке пользователей отсутствует пользователь с ID: "
                        + regularUser2.getId()));

        assertAll(
                () -> assertEquals(regularUser1.getUsername(), user.getUsername()),
                () -> assertEquals(UserRole.USER, user.getRole()));

        // 3. ------------------ USER РЕГИСТРИРУЕТСЯ С РОЛЬЮ MODERATOR. ------------------

        String username2 = "Roman Romanov";
        registrationRequest = Map.of(
                "username", username2,
                "password", "password",
                "role", "MODERATOR"
        ); // Проверим, что пользователь не может сам себе присвоить роль ADMIN

        UserDto createdUserDto2 = webTestClient.post()
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
                () -> assertNotNull(createdUserDto2.getId()),
                () -> assertEquals(username2, createdUserDto2.getUsername()),
                () -> assertEquals(UserStatus.ACTIVE, createdUserDto2.getStatus()),
                () -> assertEquals(UserRole.USER, createdUserDto2.getRole()));

        // 4. ------------------ USER МЕНЯЕТ СВОЮ РОЛЬ НА MODERATOR. ------------------

        updateStatusRequest = Map.of(
                "username", username,
                "role", "MODERATOR");

        clientWithAuth(user1Token).put()
                .uri("/api/users/" + regularUser1.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateStatusRequest)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message").isEqualTo("You do not have permission to perform this action.");

        User user2 = userRepository.findById(regularUser1.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "В списке пользователей отсутствует пользователь с ID: "
                        + regularUser2.getId()));

        assertAll(
                () -> assertEquals(regularUser1.getUsername(), user2.getUsername()),
                () -> assertEquals(UserRole.USER, user2.getRole()));


        // 5. ------------------ADMIN МЕНЯЕТ РОЛЬ USER НА ADMIN. ------------------

        updateStatusRequest = Map.of(
                "username", regularUser1.getUsername(),
                "role", "ADMIN");

        clientWithAuth(adminToken).put()
                .uri("/api/users/" + regularUser1.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateStatusRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserDto.class)
                .value(userDto -> {
                    assertAll(
                            () -> assertEquals(regularUser1.getId(), userDto.getId()),
                            () -> assertEquals(regularUser1.getUsername(), userDto.getUsername()),
                            () -> assertEquals(regularUser1.getStatus(), userDto.getStatus()),
                            () -> assertEquals(UserRole.ADMIN, userDto.getRole())
                    );
                });

        User userAdmin = userRepository.findById(regularUser1.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "В списке пользователей отсутствует пользователь с ID: "
                        + regularUser1.getId()));

        assertAll(
                () -> assertEquals(regularUser1.getUsername(), userAdmin.getUsername()),
                () -> assertEquals(UserRole.ADMIN, userAdmin.getRole()));


        // 6. ------------------ADMIN МЕНЯЕТ РОЛЬ USER НА MODERATOR. ------------------

        updateStatusRequest = Map.of(
                "username", regularUser2.getUsername(),
                "role", "MODERATOR");

        clientWithAuth(adminToken).put()
                .uri("/api/users/" + regularUser2.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateStatusRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(UserDto.class)
                .value(userDto -> {
                    assertAll(
                            () -> assertEquals(regularUser2.getId(), userDto.getId()),
                            () -> assertEquals(regularUser2.getUsername(), userDto.getUsername()),
                            () -> assertEquals(regularUser2.getStatus(), userDto.getStatus()),
                            () -> assertEquals(UserRole.MODERATOR, userDto.getRole())
                    );
                });

        User userModer = userRepository.findById(regularUser2.getId()).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "В списке пользователей отсутствует пользователь с ID: "
                        + regularUser2.getId()));

        assertAll(
                () -> assertEquals(regularUser2.getUsername(), userModer.getUsername()),
                () -> assertEquals(UserRole.MODERATOR, userModer.getRole()));
    }

    @DisplayName("""
            Просмотр всех пользователей.
             1. USER получает список всех пользователей.
             2. ADMIN получает список всех пользователей.
             3. MODERATOR получает список всех пользователей.
             """)
    @Test
    void findAllUsersTest() {

        List<User> allUsers = userRepository.findAll();

        List<UserDto> userDtoList = allUsers.stream().map(UserMapper::toDto).toList();


        // 1. ------------------ USER ПОЛУЧАЕТ ВСЕХ ПОЛЬЗОВАТЕЛЕЙ. ------------------

        clientWithAuth(user1Token).get()
                .uri("/api/users")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.message")
                .isEqualTo("You do not have permission to perform this action.");

        // 2. ------------------ ADMIN ПОЛУЧАЕТ ВСЕХ ПОЛЬЗОВАТЕЛЕЙ. ------------------

        clientWithAuth(adminToken).get()
                .uri("/api/users")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserDto.class)
                .value(list -> {
                    assertFalse(list.isEmpty());
                    assertThat(list)
                            .usingRecursiveFieldByFieldElementComparator()
                            .containsExactlyInAnyOrderElementsOf(userDtoList);
                });


        // 2. ------------------ ADMIN ПОЛУЧАЕТ ВСЕХ ПОЛЬЗОВАТЕЛЕЙ. ------------------

        clientWithAuth(moderatorToken).get()
                .uri("/api/users")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(UserDto.class)
                .value(list -> {
                    assertFalse(list.isEmpty());
                    assertThat(list)
                            .usingRecursiveFieldByFieldElementComparator()
                            .containsExactlyInAnyOrderElementsOf(userDtoList);
                });
    }

    @DisplayName("""
             Проверка механизма tokenVersion и инвалидации сессий:
             1. USER успешно обращается к защищенному эндпоинту.
             2. USER делает Logout (token_version в БД инкрементируется).
             3. USER пытается повторно зайти по старому токену и получает 403 Forbidden.""")
    @Test
    void tokenVersionInvalidationWorkFlowTest() {
        // 1. ------------------ USER ЗАПРАШИВАЕТ СВОЙ ПРОФИЛЬ ПО ID. ------------------
        clientWithAuth(user1Token).get()
                .uri("/api/users/" + regularUser1.getId())
                .exchange()
                .expectStatus().isOk();

        // 2. -------------------------- USER ДЕЛАЕТ LOGOUT. ---------------------------
        clientWithAuth(user1Token).post()
                .uri("/api/auth/logout")
                .exchange()
                .expectStatus().isNoContent();

        // 3. --------- USER ПЫТАЕТСЯ ЗАЙТИ В СВОЙ ПРОФИЛЬ ИСПОЛЬЗЯ СТАРЫЙ ТОКЕН --------
        clientWithAuth(user1Token).get()
                .uri("/api/users/" + regularUser1.getId())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Access Denied")
                .jsonPath("$.message").isEqualTo("Your account is blocked. Please contact support.");
    }

    @DisplayName("""
            Проверка механизма tokenVersion при блокировании пользователя ADMIN:
            1. USER запрашивает свой профиль по ID.
            2. ADMIN блокирует пользователя.
            3. USER совершает повторный запрос своего профиля по ID.""")
    @Test
    void tokenVersionAdminBlockedUserSuccessTest() {
        // 1. ----------------------- USER ЗАПРАШИВАЕТ СВОЙ ПРОФИЛЬ ПО ID. --------------------
        clientWithAuth(user1Token).get()
                .uri("/api/users/" + regularUser1.getId())
                .exchange()
                .expectStatus().isOk();

        // 2. -------------------------- ADMIN БЛОКИРУЕТ ПОЛЬЗОВАТЕЛЯ. ------------------------
        Map<String, Object> updateStatusRequest = Map.of(
                "username", regularUser1.getUsername(),
                "status", "BLOCKED");
        clientWithAuth(adminToken).put()
                .uri("/api/users/" + regularUser1.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(updateStatusRequest)
                .exchange()
                .expectStatus().isOk();

        // 3. ----------------------- USER ПОВТОРНО ЗАПРАШИВАЕТ СВОЙ ПРОФИЛЬ ПО ID. --------------------
        clientWithAuth(user1Token).get()
                .uri("/api/users/" + regularUser1.getId())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Access Denied")
                .jsonPath("$.message").isEqualTo("Your account is blocked. Please contact support.");
    }
}
