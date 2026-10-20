package com.nosulkora.filestorage;

import com.nosulkora.filestorage.factory.UserTestDataFactory;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.repository.EventRepository;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.security.JwtTokenProvider;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.MySQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// ИСПРАВЛЕНИЕ: Отключаем дефолтную реактивную генерацию паролей в тестах
@EnableAutoConfiguration(exclude = {ReactiveUserDetailsServiceAutoConfiguration.class})
public abstract class BaseIntegrationTest {

    protected static final MySQLContainer<?> MYSQL_CONTAINER;
    protected static final MinIOContainer MINIO_CONTAINER;

    static {
        MYSQL_CONTAINER = new MySQLContainer<>("mysql:8.0.36")
                .withDatabaseName("file_storage_db")
                .withUsername("test_user")
                .withPassword("test_password");


        MINIO_CONTAINER = new MinIOContainer("minio/minio:latest")
                .withUserName("minioadmin")
                .withPassword("minioadmin");

        // Принудительный запуск контейнеров до инициализации контекста Spring
        MYSQL_CONTAINER.start();
        MINIO_CONTAINER.start();
    }

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    protected FileRepository fileRepository;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected EventRepository eventRepository;

    @Autowired
    protected JwtTokenProvider tokenProvider;

    @Autowired
    protected UserTestDataFactory userFactory;

    @Autowired
    protected MinioClient testMinioClient;

    // Готовый глобальный пул пользователей и токенов для любых наследников
    protected User adminUser;
    protected User moderatorUser;
    protected User regularUser1;
    protected User regularUser2;

    protected String adminToken;
    protected String moderatorToken;
    protected String user1Token;
    protected String user2Token;

    @BeforeEach
    void setUp() {
        // 1. Очищаем базу данных перед каждым тестом
        eventRepository.deleteAll();
        fileRepository.deleteAll();
        userRepository.deleteAll();

        // 2. Инициализируем пул пользователей через фабрику
        adminUser = userFactory.builder()
                .username("test_admin")
                .role(UserRole.ADMIN)
                .build();

        moderatorUser = userFactory.builder()
                .username("test_moderator")
                .role(UserRole.MODERATOR)
                .build();

        regularUser1 = userFactory.builder()
                .username("test_user1")
                .build();

        regularUser2 = userFactory.builder()
                .username("test_user2")
                .build();

        // 3. Генерируем готовые токены для авторизации в HTTP-запросах тестов
        adminToken = generateTokenFor(adminUser);
        moderatorToken = generateTokenFor(moderatorUser);
        user1Token = generateTokenFor(regularUser1);
        user2Token = generateTokenFor(regularUser2);
    }

    /**
     * Быстрая генерация авторизированного клиента.
     */
    protected WebTestClient clientWithAuth(String token) {
        return webTestClient.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    @DynamicPropertySource
    static void configProperties(DynamicPropertyRegistry registry) {
        // Динамически подменяем настройки БД для Hibernate и Flyway
        registry.add("spring.datasource.url", MYSQL_CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL_CONTAINER::getUsername);
        registry.add("spring.datasource.password", MYSQL_CONTAINER::getPassword);

        // Динамически подменяем настройки MinIO
        registry.add("minio.endpoint", MINIO_CONTAINER::getS3URL);
        registry.add("minio.access-key", MINIO_CONTAINER::getUserName);
        registry.add("minio.secret-key", MINIO_CONTAINER::getPassword);
        registry.add("minio.bucket-name", () -> "user-files");

        // Отключаем асинхронный запуск Flyway для тестов, чтобы миграции выполнялись синхронно
        registry.add("spring.flyway.enabled", () -> "true");
    }

    // ----- Вспомогательные методы -----

    private String generateTokenFor(User user) {
        return tokenProvider.createToken(
                user.getId(),
                user.getUsername(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getTokenVersion()
        );
    }
}
