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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;

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

    @Test
    @DisplayName("Успешное обновление события пользователем с ролью ADMIN")
    void updateSuccessAdminTest() {
        EventDto eventDto = new EventDto();
        eventDto.setId(100);
        eventDto.setStatus(EventStatus.UPDATED);

        UserPrincipal principal = new UserPrincipal(1, "adminUser", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        User mockUser = new User();
        mockUser.setId(1);
        File mockFile = new File();
        mockFile.setId(100);

        Event existingEvent = new Event();
        existingEvent.setId(100);
        existingEvent.setStatus(EventStatus.CREATED);
        existingEvent.setUser(mockUser);
        existingEvent.setFile(mockFile);

        when(eventRepository.findById(100)).thenReturn(Optional.of(existingEvent));
        when(eventRepository.save(any(Event.class))).thenReturn(existingEvent);

        Mono<EventDto> result = eventService.update(eventDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(100)
                                          && dto.getStatus() == EventStatus.UPDATED)
                .verifyComplete();

        verify(eventRepository, times(1)).findById(100);
        verify(eventRepository, times(1)).save(any(Event.class));
    }

    @Test
    @DisplayName("Ошибка обновления события: у роли USER нет доступа")
    void updateAccessDeniedUserTest() {
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

        verify(eventRepository, never()).save(any(Event.class));
    }
}
