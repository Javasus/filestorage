package com.nosulkora.filestorage.component;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.model.dto.EventDto;
import com.nosulkora.filestorage.model.entity.Event;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.EventStatus;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.repository.EventRepository;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.security.UserPrincipal;
import com.nosulkora.filestorage.service.impl.EventServiceImpl;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class EventServiceImplTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private FileRepository fileRepository;

    @InjectMocks
    private EventServiceImpl eventService;

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
    @DisplayName("Успешное создание события.")
    void createSuccessTest() {
        // Данные для теста
        EventDto eventDto = new EventDto();
        eventDto.setUserId(1);
        eventDto.setFileId(10);
        eventDto.setStatus(EventStatus.CREATED);

        User mockUser = new User();
        mockUser.setId(1);

        File mockFile = new File();
        mockFile.setId(10);

        Event mockSavedEvent = new Event();
        mockSavedEvent.setId(100);
        mockSavedEvent.setUser(mockUser);
        mockSavedEvent.setFile(mockFile);
        mockSavedEvent.setStatus(EventStatus.CREATED);
        mockSavedEvent.setTimeStamp(LocalDateTime.now());

        when(userRepository.findById(1)).thenReturn(Optional.of(mockUser));
        when(fileRepository.findById(10)).thenReturn(Optional.of(mockFile));
        when(eventRepository.save(any(Event.class))).thenReturn(mockSavedEvent);

        Mono<EventDto> result = eventService.create(eventDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(100)
                                          && dto.getUserId().equals(1)
                                          && dto.getFileId().equals(10)
                                          && dto.getStatus().equals(EventStatus.CREATED)
                ).verifyComplete();
        verify(eventRepository, times(1)).save(any(Event.class));
        verify(userRepository, times(1)).findById(1);
        verify(fileRepository, times(1)).findById(10);
    }

    @Test
    @DisplayName("Ошибка создания события: Пользователь не найден")
    void createUserNotFoundTest() {
        // Данные для теста
        EventDto eventDto = new EventDto();
        eventDto.setUserId(1);
        eventDto.setFileId(10);

        when(userRepository.findById(1)).thenReturn(Optional.empty());
        when(fileRepository.findById(10)).thenReturn(Optional.of(new File()));

        Mono<EventDto> result = eventService.create(eventDto);

        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof NotFoundException
                                                 && throwable.getMessage().equals("User not found by ID: 1")
                ).verify();

        verify(userRepository, times(1)).findById(1);
        verify(fileRepository, times(1)).findById(10);
        verify(eventRepository, never()).save(any(Event.class));
    }

    // ----------------------------------------------------- USER ------------------------------------------------------

    @Test
    @DisplayName("Ошибка обновления события: у роли USER нет доступа")
    void updateUserAccessDeniedTest() {
        EventDto eventDto = new EventDto();
        eventDto.setId(100);
        eventDto.setStatus(EventStatus.UPDATED);

        UserPrincipal principal = new UserPrincipal(1, "simpleUser", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<EventDto> result = eventService.update(eventDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("Only MODERATOR or ADMIN can manage events")
                ).verify();

        verify(eventRepository, never()).findById(any(Integer.class));
        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка удаления события: у роли USER нет доступа")
    void deleteUserAccessDeniedTest() {
        Integer eventId = 50;
        UserPrincipal principal = new UserPrincipal(1, "simpleUser", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<Void> result = eventService.delete(eventId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("Only MODERATOR or ADMIN can manage events")
                ).verify();

        verify(eventRepository, never()).findById(eventId);
        verify(eventRepository, never()).deleteById(eventId);
    }

    @Test
    @DisplayName("Ошибка получение события по ID: USER пытается прочесть чужое событие")
    void findByIdUserAccessDeniedUserTest() {
        Integer eventId = 100;

        User mockUser = new User();
        mockUser.setId(5);

        Event event = new Event();
        event.setId(eventId);
        event.setUser(mockUser);

        UserPrincipal principal = new UserPrincipal(1, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        Mono<EventDto> result = eventService.findById(eventId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("You do not have permission to view this event")
                ).verify();

        verify(eventRepository, times(1)).findById(eventId);
    }

    @Test
    @DisplayName("Получение события по ID: USER успешно получает своё событие")
    void findByIdUserSuccessTest() {
        Integer eventId = 100;
        Integer userId = 5;

        User mockUser = new User();
        mockUser.setId(userId);

        Event event = new Event();
        event.setId(eventId);
        event.setUser(mockUser);

        UserPrincipal principal = new UserPrincipal(userId, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        Mono<EventDto> result = eventService.findById(eventId);

        StepVerifier.create(result)
                .expectNextMatches((dto -> dto.getId().equals(eventId)))
                .verifyComplete();

        verify(eventRepository, times(1)).findById(eventId);
    }

    @Test
    @DisplayName("Получение всех событий: USER успешно получает список только своих событий")
    void findAllUserAccessDeniedTest() {
        Integer eventId = 10;
        Integer userId = 5;

        User mockUser = new User();
        mockUser.setId(userId);

        Event event = new Event();
        event.setId(eventId);
        event.setUser(mockUser);
        event.setFile(new File());

        UserPrincipal principal = new UserPrincipal(userId, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findByUserId(userId)).thenReturn(List.of(event));

        Flux<EventDto> result = eventService.findAll();

        StepVerifier.create(result)
                .expectNextMatches((dto -> dto.getId().equals(eventId)))
                .verifyComplete();

        verify(eventRepository, times(1)).findByUserId(userId);
        verify(eventRepository, never()).findAll();
    }

    // ----------------------------------------------------- ADMIN -----------------------------------------------------

    @Test
    @DisplayName("Успешное обновление события пользователем с ролью ADMIN")
    void updateAdminSuccessTest() {
        Integer eventId = 100;
        EventDto eventDto = new EventDto();
        eventDto.setId(eventId);
        eventDto.setStatus(EventStatus.UPDATED);

        UserPrincipal principal = new UserPrincipal(1, "adminUser", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        User mockUser = new User();
        mockUser.setId(1);
        File mockFile = new File();
        mockFile.setId(eventId);

        Event existingEvent = new Event();
        existingEvent.setId(eventId);
        existingEvent.setStatus(EventStatus.CREATED);
        existingEvent.setUser(mockUser);
        existingEvent.setFile(mockFile);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(existingEvent));
        when(eventRepository.save(any(Event.class))).thenReturn(existingEvent);

        Mono<EventDto> result = eventService.update(eventDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(eventId)
                                          && dto.getStatus() == EventStatus.UPDATED)
                .verifyComplete();

        verify(eventRepository, times(1)).findById(eventId);
        verify(eventRepository, times(1)).save(any(Event.class));
    }

    @Test
    @DisplayName("Успешное удаление события админом")
    void deleteAdminSuccessTest() {
        Integer eventId = 50;
        UserPrincipal principal = new UserPrincipal(1, "adminUser", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Event existingEvent = new Event();
        existingEvent.setId(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(existingEvent));
        doNothing().when(eventRepository).deleteById(eventId);

        Mono<Void> result = eventService.delete(eventId);

        StepVerifier.create(result).verifyComplete();

        verify(eventRepository, times(1)).findById(eventId);
        verify(eventRepository, times(1)).deleteById(eventId);
    }

    @Test
    @DisplayName("Ошибка удаления события ADMIN: событие не найдено в БД")
    void deleteAdminNotFoundEventTest() {
        Integer eventId = 50;
        UserPrincipal principal = new UserPrincipal(1, "adminUser", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        Mono<Void> result = eventService.delete(eventId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("Event not found by ID: " + eventId)
                ).verify();

        verify(eventRepository, times(1)).findById(eventId);
        verify(eventRepository, never()).deleteById(eventId);
    }

    @Test
    @DisplayName("Получение события по ID админом")
    void findByIdAdminSuccessTest() {
        Integer eventId = 100;
        Event event = new Event();
        event.setId(eventId);

        UserPrincipal principal = new UserPrincipal(1, "adminUser", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        Mono<EventDto> result = eventService.findById(eventId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(eventId))
                .verifyComplete();

        verify(eventRepository, times(1)).findById(eventId);
    }

    @Test
    @DisplayName("Ошибка получение события по ID ADMIN: событие не найдено в БД")
    void findByIdAdminNotFoundEventTest() {
        Integer eventId = 100;
        UserPrincipal principal = new UserPrincipal(1, "adminUser", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        Mono<EventDto> result = eventService.findById(eventId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("Event not found by ID: " + eventId)
                ).verify();

        verify(eventRepository, times(1)).findById(eventId);
    }

    @Test
    @DisplayName("Получение админом всем событий")
    void findAllAdminSuccessTest() {
        Event event1 = new Event();
        event1.setId(1);
        event1.setFile(new File());
        event1.setUser(new User());

        Event event2 = new Event();
        event2.setId(2);
        event2.setFile(new File());
        event2.setUser(new User());

        UserPrincipal principal = new UserPrincipal(1, "adminUser", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findAll()).thenReturn(List.of(event1, event2));

        Flux<EventDto> result = eventService.findAll();

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(1))
                .expectNextMatches(dto -> dto.getId().equals(2))
                .verifyComplete();

        verify(eventRepository, times(1)).findAll();
    }

    // --------------------------------------------------- MODERATOR ---------------------------------------------------

    @Test
    @DisplayName("Успешное обновление события пользователем с ролью MODERATOR")
    void updateModeratorSuccessTest() {
        Integer eventId = 100;
        EventDto eventDto = new EventDto();
        eventDto.setId(eventId);
        eventDto.setStatus(EventStatus.UPDATED);

        UserPrincipal principal = new UserPrincipal(1, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        User mockUser = new User();
        mockUser.setId(1);
        File mockFile = new File();
        mockFile.setId(eventId);

        Event existingEvent = new Event();
        existingEvent.setId(eventId);
        existingEvent.setStatus(EventStatus.CREATED);
        existingEvent.setUser(mockUser);
        existingEvent.setFile(mockFile);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(existingEvent));
        when(eventRepository.save(any(Event.class))).thenReturn(existingEvent);

        Mono<EventDto> result = eventService.update(eventDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(eventId)
                                          && dto.getStatus() == EventStatus.UPDATED)
                .verifyComplete();

        verify(eventRepository, times(1)).findById(eventId);
        verify(eventRepository, times(1)).save(any(Event.class));
    }

    @Test
    @DisplayName("Успешное удаление события MODERATOR")
    void deleteModeratorSuccessTest() {
        Integer eventId = 50;
        UserPrincipal principal = new UserPrincipal(1, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Event existingEvent = new Event();
        existingEvent.setId(eventId);

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(existingEvent));
        doNothing().when(eventRepository).deleteById(eventId);

        Mono<Void> result = eventService.delete(eventId);

        StepVerifier.create(result).verifyComplete();

        verify(eventRepository, times(1)).findById(eventId);
        verify(eventRepository, times(1)).deleteById(eventId);
    }

    @Test
    @DisplayName("Ошибка удаления события MODERATOR: событие не найдено в БД")
    void deleteModeratorNotFoundEventTest() {
        Integer eventId = 50;
        UserPrincipal principal = new UserPrincipal(1, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        Mono<Void> result = eventService.delete(eventId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("Event not found by ID: " + eventId)
                ).verify();

        verify(eventRepository, times(1)).findById(eventId);
        verify(eventRepository, never()).deleteById(eventId);
    }

    @Test
    @DisplayName("Получение всех Модератором")
    void findAllModeratorSuccessTest() {
        UserPrincipal principal = new UserPrincipal(1, "moderatorUser", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Event event1 = new Event();
        event1.setId(1);
        event1.setFile(new File());
        event1.setUser(new User());

        Event event2 = new Event();
        event2.setId(2);
        event2.setFile(new File());
        event2.setUser(new User());

        when(eventRepository.findAll()).thenReturn(List.of(event1, event2));

        Flux<EventDto> result = eventService.findAll();

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(1))
                .expectNextMatches(dto -> dto.getId().equals(2))
                .verifyComplete();

        verify(eventRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("Получение события по ID MODERATOR")
    void findByIdModeratorSuccessTest() {
        Integer eventId = 100;

        User mockUser = new User();
        mockUser.setId(5);

        Event event = new Event();
        event.setId(eventId);
        event.setUser(mockUser);
        event.setFile(new File());

        UserPrincipal principal = new UserPrincipal(1, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        Mono<EventDto> result = eventService.findById(eventId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(eventId))
                .verifyComplete();

        verify(eventRepository, times(1)).findById(eventId);
    }

    @Test
    @DisplayName("Ошибка получение события по ID MODERATOR: событие не найдено в БД")
    void findByIdModeratorNotFoundEventTest() {
        Integer eventId = 100;
        UserPrincipal principal = new UserPrincipal(1, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        Mono<EventDto> result = eventService.findById(eventId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("Event not found by ID: " + eventId)
                ).verify();

        verify(eventRepository, times(1)).findById(eventId);
    }
}
