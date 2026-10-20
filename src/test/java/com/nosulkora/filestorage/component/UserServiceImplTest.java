package com.nosulkora.filestorage.component;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.model.dto.AuthRequestDto;
import com.nosulkora.filestorage.model.dto.AuthResponseDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.repository.UserSecurityInfo;
import com.nosulkora.filestorage.security.JwtTokenProvider;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.security.UserPrincipal;
import com.nosulkora.filestorage.service.UserSecurityChecker;
import com.nosulkora.filestorage.service.UserValidator;
import com.nosulkora.filestorage.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private UserSecurityChecker userSecurityChecker;

    private UserValidator userValidator;

    private UserServiceImpl userService;

    private MockedStatic<SecurityUtils> mockedSecurityUtils;

    @BeforeEach
    void setUp() {
        // Открываем мок для статических методов SecurityUtils перед каждым тестом
        mockedSecurityUtils = Mockito.mockStatic(SecurityUtils.class);

        userValidator = new UserValidator(userRepository);

        userSecurityChecker = new UserSecurityChecker(userRepository);

        userService = new UserServiceImpl(
                userRepository,
                passwordEncoder,
                jwtTokenProvider,
                userValidator, // Используется реальный объект!
                userSecurityChecker
        );
    }

    @AfterEach
    void tearDown() {
        // Обязательно закрываем статический мок во избежание утечек памяти
        mockedSecurityUtils.close();
    }

    @Test
    @DisplayName("Успешное создание пользователя")
    void createUserSuccessTest() {
        String username = "Roman";
        UserDto inputUserDto = new UserDto();
        inputUserDto.setUsername(username);
        inputUserDto.setPassword("roman_password");
        inputUserDto.setRole(UserRole.USER);

        User savedUser = new User();
        savedUser.setId(1);
        savedUser.setUsername(username);
        savedUser.setRole(UserRole.USER);
        savedUser.setStatus(UserStatus.ACTIVE);
        savedUser.setTokenVersion(1);

        when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(inputUserDto.getPassword())).thenReturn("encoded_hash");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        Mono<UserDto> result = userService.create(inputUserDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(1)
                                          && dto.getUsername().equals(username)
                                          && dto.getRole().equals(UserRole.USER)
                                          && dto.getStatus().equals(UserStatus.ACTIVE)
                ).verifyComplete();

        verify(userRepository, times(1)).findByUsername(username);
        verify(passwordEncoder, times(1)).encode(inputUserDto.getPassword());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка создания пользователя: имя пользователя пустое")
    void createEmptyUsernameTest() {
        UserDto inputUserDto = new UserDto();
        inputUserDto.setUsername("");

        assertThrows(ValidationException.class, () -> userService.create(inputUserDto));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка создания пользователя: пароль пользователя пустой")
    void createEmptyPasswordTest() {
        UserDto inputUserDto = new UserDto();
        inputUserDto.setUsername("Roman");
        inputUserDto.setPassword("");

        assertThrows(ValidationException.class, () -> userService.create(inputUserDto));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка создания пользователя: имя пользователя уже занято")
    void createUsernameExistTest() {
        String username = "Roman";
        UserDto inputUserDto = new UserDto();
        inputUserDto.setUsername(username);
        inputUserDto.setPassword("password");

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(new User()));

        Mono<UserDto> result = userService.create(inputUserDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof ValidationException
                                     && throwable.getMessage()
                                             .equals("User with username " + username + " already exists")
                ).verify();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Успешное обновление пользователем своего профиля - USER")
    void updateUserSuccessTest() {
        String newUsername = "new_name";
        String oldUsername = "old_name";
        UserDto inputUserDto = new UserDto();
        inputUserDto.setId(1);
        inputUserDto.setUsername(newUsername);

        UserPrincipal principal = new UserPrincipal(1, "oldUsername", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        User updatedUser = new User();
        updatedUser.setId(1);
        updatedUser.setUsername(oldUsername);
        updatedUser.setRole(UserRole.USER);
        updatedUser.setStatus(UserStatus.ACTIVE);

        when(userRepository.findById(inputUserDto.getId())).thenReturn(Optional.of(updatedUser));
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        Mono<UserDto> result = userService.update(inputUserDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getUsername().equals(newUsername))
                .verifyComplete();

        verify(userRepository, times(1)).findById(inputUserDto.getId());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка обновления пользователя: Пользователь которого хочет обновить ADMIN не найден")
    void updateUserNotFoundAsAdminTest() {
        Integer userId = 1;
        UserDto userDto = new UserDto();
        userDto.setUsername("username");
        userDto.setId(userId);

        UserPrincipal principal = new UserPrincipal(2, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        Mono<UserDto> result = userService.update(userDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("User not found with ID: " + userId)
                ).verify();

        verify(userRepository, times(1)).findById(userId);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка обновления пользователя: USER пытается обновить чужой профиль")
    void updateForeignProfileAsUserAccessDeniedTest() {
        UserDto inputUserDto = new UserDto();
        inputUserDto.setId(1);
        inputUserDto.setUsername("username");

        UserPrincipal principal = new UserPrincipal(2, "hacked_name", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<UserDto> result = userService.update(inputUserDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("You do not have permission to perform this action.")
                ).verify();

        verify(userRepository, never()).findById(inputUserDto.getId());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка обновления пользователя: имя пользователя пустое")
    void updateEmptyUsernameTest() {
        UserDto inputUserDto = new UserDto();
        inputUserDto.setUsername("");

        assertThrows(ValidationException.class, () -> userService.update(inputUserDto));

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("ADMIN успешно меняет РОЛЬ чужого пользователя")
    void updateChangeRoleAsAdminSuccessTest() {
        String username = "Roman";
        UserDto inputUserDto = new UserDto();
        inputUserDto.setId(1);
        inputUserDto.setUsername(username);
        inputUserDto.setRole(UserRole.MODERATOR);

        User updatedUser = new User();
        updatedUser.setId(1);
        updatedUser.setUsername(username);
        updatedUser.setRole(UserRole.USER);

        UserPrincipal principal = new UserPrincipal(2, "Admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(1)).thenReturn(Optional.of(updatedUser));
        when(userRepository.save(any(User.class))).thenReturn(updatedUser);

        Mono<UserDto> result = userService.update(inputUserDto);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getRole().equals(UserRole.MODERATOR)
                                          && dto.getId().equals(1)
                                          && dto.getUsername().equals(username))
                .verifyComplete();

        verify(userRepository, times(1)).findById(inputUserDto.getId());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка обновления пользователя: USER меняет роль другого пользователя на ADMIN")
    void updateChangeRoleAnotherUserAsUserAccessDeniedTest() {
        String username = "Roman";
        UserDto inputUserDto = new UserDto();
        inputUserDto.setId(1);
        inputUserDto.setUsername(username);
        inputUserDto.setRole(UserRole.ADMIN);

        UserPrincipal principal = new UserPrincipal(2, "Hacker_name", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<UserDto> result = userService.update(inputUserDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("You do not have permission to perform this action.")
                ).verify();

        verify(userRepository, never()).findById(inputUserDto.getId());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка обновления пользователя: USER меняет свою роль на ADMIN. В результате роль не меняется.")
    void updateChangeRoleHimselfAsUserAccessDeniedTest() {
        String username = "Roman";
        Integer userId = 1;
        UserDto inputUserDto = new UserDto();
        inputUserDto.setId(userId);
        inputUserDto.setUsername(username);
        inputUserDto.setRole(UserRole.ADMIN);

        User userFromDb = new User();
        userFromDb.setId(userId);
        userFromDb.setUsername(username);
        userFromDb.setRole(UserRole.USER);
        userFromDb.setStatus(UserStatus.ACTIVE);

        UserPrincipal principal = new UserPrincipal(userId, username, UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(1)).thenReturn(Optional.of(userFromDb));

        Mono<UserDto> result = userService.update(inputUserDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("You do not have permission to perform this action.")
                ).verify();

        verify(userRepository, times(1)).findById(inputUserDto.getId());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Пользователь с ролью USER успешно удаляет себя. Положительный.")
    void deleteHimselfUserSuccessTest() {
        Integer userId = 1;
        String username = "username";

        User deletedUser = new User();
        deletedUser.setId(userId);
        deletedUser.setStatus(UserStatus.ACTIVE);
        deletedUser.setUsername(username);
        deletedUser.setRole(UserRole.USER);

        UserPrincipal principal = new UserPrincipal(1, username, UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(userId)).thenReturn(Optional.of(deletedUser));
        when(userRepository.save(any(User.class))).thenReturn(deletedUser);

        Mono<Void> result = userService.delete(userId);

        StepVerifier.create(result).verifyComplete();

        verify(userRepository, times(1)).findById(userId);
        verify(userRepository, times(1)).save(
                argThat(userDto ->
                        userDto.getId().equals(principal.getId()) &&
                        userDto.getStatus().equals(UserStatus.BLOCKED) &&
                        userDto.getUsername().equals(username)
                )
        );
        verify(userRepository, never()).deleteById(userId);
    }

    @Test
    @DisplayName("ADMIN удаляет пользователя. Положительный.")
    void deleteUserAsAdminSuccessTest() {
        Integer userId = 1;
        User user = new User();
        user.setId(userId);

        UserPrincipal principal = new UserPrincipal(2, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        Mono<Void> result = userService.delete(userId);

        StepVerifier.create(result).verifyComplete();

        verify(userRepository, times(1)).findById(userId);
        verify(userRepository, times(1)).save(
                argThat(userDto ->
                        userDto.getId().equals(userId) &&
                        userDto.getStatus().equals(UserStatus.BLOCKED)
                )
        );
        verify(userRepository, never()).deleteById(userId);
    }

    @Test
    @DisplayName("Ошибка удаления пользователя: пользователь удаляет другого пользователя")
    void deleteAnotherUserAsUserAccessDeniedTest() {
        Integer userId = 1;

        UserPrincipal principal = new UserPrincipal(2, "another_username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<Void> result = userService.delete(userId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("You do not have permission to perform this action.")
                ).verify();

        verify(userRepository, never()).findById(userId);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Ошибка удаления пользователя: Пользователь которого хочет удалить ADMIN не найден")
    void deleteUserNotFoundAsAdminTest() {
        Integer userId = 1;

        UserPrincipal principal = new UserPrincipal(2, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        Mono<Void> result = userService.delete(userId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException
                                     && throwable.getMessage().equals("User not found with ID: " + userId)
                ).verify();

        verify(userRepository, times(1)).findById(userId);
        verify(userRepository, never()).deleteById(userId);
    }

    @Test
    @DisplayName("Пользователь ищет себя по ID")
    void findByIdHimselfUserSuccessTest() {
        Integer userId = 1;
        User user = new User();
        user.setId(userId);

        UserPrincipal principal = new UserPrincipal(1, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Mono<UserDto> result = userService.findById(userId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(userId))
                .verifyComplete();

        verify(userRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("ADMIN ищет пользователя по ID")
    void findByIdUserAsAdminSuccessTest() {
        Integer userId = 1;
        User user = new User();
        user.setId(userId);

        UserPrincipal principal = new UserPrincipal(2, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Mono<UserDto> result = userService.findById(userId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(userId))
                .verifyComplete();

        verify(userRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("MODERATOR ищет пользователя по ID")
    void findByIdUserAsModeratorSuccessTest() {
        Integer userId = 1;
        User user = new User();
        user.setId(userId);

        UserPrincipal principal = new UserPrincipal(2, "moderator", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Mono<UserDto> result = userService.findById(userId);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(userId))
                .verifyComplete();

        verify(userRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("Ошибка поиска по ID: USER ищет другого пользователя")
    void findByIdUserAsUserAccessDeniedTest() {
        Integer userId = 2;
        UserPrincipal principal = new UserPrincipal(1, "hacker_user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<UserDto> result = userService.findById(userId);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage()
                                             .equals("You do not have permission to perform this action.")
                ).verify();

        verify(userRepository, never()).findById(userId);
    }

    @Test
    @DisplayName("ADMIN ищет всех пользователей")
    void findAllAsAdminSuccessTest() {
        User user1 = new User();
        user1.setId(1);
        user1.setUsername("username1");
        user1.setRole(UserRole.USER);
        user1.setStatus(UserStatus.ACTIVE);

        User user2 = new User();
        user2.setId(2);
        user2.setUsername("username2");
        user2.setRole(UserRole.USER);
        user2.setStatus(UserStatus.BLOCKED);

        UserPrincipal principal = new UserPrincipal(3, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findAll()).thenReturn(List.of(user1, user2));

        Flux<UserDto> result = userService.findAll();

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getId().equals(user1.getId()))
                .expectNextMatches(dto -> dto.getId().equals(user2.getId()))
                .verifyComplete();

        verify(userRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("Ошибка поиска всех пользователей: User ищет всех пользователей")
    void findAllAsUserAccessDeniedTest() {
        UserPrincipal principal = new UserPrincipal(3, "user", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Flux<UserDto> result = userService.findAll();

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage()
                                             .equals("You do not have permission to perform this action.")
                ).verify();

        verify(userRepository, never()).findAll();
    }

    @Test
    @DisplayName("USER ищет себя по имени")
    void findByUsernameHimselfAsUserSuccessTest() {
        String username = "Evgeniy";
        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setStatus(UserStatus.ACTIVE);
        user.setRole(UserRole.USER);

        UserPrincipal principal = new UserPrincipal(1, username, UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));

        Mono<UserDto> result = userService.findByUsername(username);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getUsername().equals(username))
                .verifyComplete();

        verify(userRepository, times(1)).findByUsername(username);
    }

    @Test
    @DisplayName("ADMIN ищет пользователя по имени")
    void findByUsernameAsAdminSuccessTest() {
        String username = "Evgeniy";
        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setStatus(UserStatus.ACTIVE);
        user.setRole(UserRole.USER);

        UserPrincipal principal = new UserPrincipal(2, "admin", UserRole.ADMIN);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));

        Mono<UserDto> result = userService.findByUsername(username);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getUsername().equals(username))
                .verifyComplete();

        verify(userRepository, times(1)).findByUsername(username);
    }

    @Test
    @DisplayName("MODERATOR ищет пользователя по имени")
    void findByUsernameAsModeratorSuccessTest() {
        String username = "Evgeniy";
        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setStatus(UserStatus.ACTIVE);
        user.setRole(UserRole.USER);

        UserPrincipal principal = new UserPrincipal(3, "moder", UserRole.MODERATOR);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));

        Mono<UserDto> result = userService.findByUsername(username);

        StepVerifier.create(result)
                .expectNextMatches(dto -> dto.getUsername().equals(username))
                .verifyComplete();

        verify(userRepository, times(1)).findByUsername(username);
    }

    @Test
    @DisplayName("Ошибка поиска пользователя по имени: USER ищет по имени другого пользователя")
    void findByUsernameAsUserAccessDeniedTest() {
        String username = "Evgeniy";
        UserPrincipal principal = new UserPrincipal(1, "username", UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));

        Mono<UserDto> result = userService.findByUsername(username);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage()
                                             .equals("You do not have permission to perform this action.")
                ).verify();

        verify(userRepository, never()).findByUsername(username);
    }

    @Test
    @DisplayName("Пользователь успешно логируется")
    void loginSuccessTest() {
        String username = "Ivan";
        String token = "jwt_token";
        AuthRequestDto authRequestDto = new AuthRequestDto();
        authRequestDto.setUsername(username);
        authRequestDto.setPassword("pass");

        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setStatus(UserStatus.ACTIVE);
        user.setRole(UserRole.USER);

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "hash")).thenReturn(true);
        when(jwtTokenProvider.createToken(1, username, "USER", "ACTIVE", 1)).thenReturn(token);

        Mono<AuthResponseDto> result = userService.login(authRequestDto);

        StepVerifier.create(result)
                .expectNextMatches(resp -> resp.getToken().equals(token))
                .verifyComplete();

        verify(userRepository, times(1)).findByUsername(username);
        verify(jwtTokenProvider, times(1))
                .createToken(1, username, "USER", "ACTIVE", 1);
    }

    @Test
    @DisplayName("ошибка логирования пользователя: Пользователь с таким именем не найден")
    void loginUsernameNotFoundAccessDeniedTest() {
        String username = "Ivan";
        AuthRequestDto authRequestDto = new AuthRequestDto();
        authRequestDto.setUsername(username);

        when(userRepository.findByUsername(username)).thenReturn(Optional.empty());

        Mono<AuthResponseDto> result = userService.login(authRequestDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("Invalid username or password")
                ).verify();

        verify(userRepository, times(1)).findByUsername(username);
        verify(jwtTokenProvider, never()).createToken(
                any(Integer.class), any(String.class), any(String.class), any(String.class), any(Integer.class));
    }

    @Test
    @DisplayName("ошибка логирования пользователя: Пользователь с таким именем заблокирован")
    void loginUserIsBlockedAccessDeniedTest() {
        String username = "Ivan";
        AuthRequestDto authRequestDto = new AuthRequestDto();
        authRequestDto.setUsername(username);

        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setStatus(UserStatus.BLOCKED);

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));

        Mono<AuthResponseDto> result = userService.login(authRequestDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException
                                     && throwable.getMessage().equals("User account is blocked")
                ).verify();

        verify(userRepository, times(1)).findByUsername(username);
        verify(jwtTokenProvider, never()).
                createToken(any(Integer.class), any(String.class), any(String.class), any(String.class), any(Integer.class));
    }

    @Test
    @DisplayName("ошибка логирования пользователя: Пользователь ввёл не верный пароль")
    void loginBadPasswordAccessDeniedTest() {
        String username = "Ivan";
        AuthRequestDto authRequestDto = new AuthRequestDto();
        authRequestDto.setUsername(username);
        authRequestDto.setPassword("pass");

        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setStatus(UserStatus.ACTIVE);

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "hash")).thenReturn(false);

        Mono<AuthResponseDto> result = userService.login(authRequestDto);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof AccessDeniedException &&
                                     throwable.getMessage().equals("Invalid username or password")
                ).verify();

        verify(userRepository, times(1)).findByUsername(username);
        verify(passwordEncoder, times(1)).matches("pass", "hash");
        verify(jwtTokenProvider, never()).createToken(
                any(Integer.class), any(String.class), any(String.class), any(String.class), any(Integer.class));
    }

    @Test
    @DisplayName("Пользователь успешно разлогируется")
    void logoutSuccessTest() {
        String username = "Roman";
        int tokenVersion = 1;
        User user = new User();
        user.setId(1);
        user.setUsername(username);
        user.setPasswordHash("hash");
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion(tokenVersion);

        UserPrincipal principal = new UserPrincipal(1, username, UserRole.USER);
        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        Mono<Void> result = userService.logout();

        StepVerifier.create(result).verifyComplete();

        verify(userRepository, times(1)).findByUsername(username);
        verify(userRepository, times(1)).save(
                argThat(userDto ->
                        userDto.getId().equals(user.getId()) &&
                        userDto.getTokenVersion().equals(tokenVersion + 1)
                )
        );
    }

    @Test
    @DisplayName("Ошибка разлогирования пользователя: Пользователь с таким именем не найден.")
    void logoutUsernameNotFoundAccessDeniedTest() {
        String username = "Roman";
        UserPrincipal principal = new UserPrincipal(1, username, UserRole.USER);

        mockedSecurityUtils.when(SecurityUtils::getCurrentUser).thenReturn(Mono.just(principal));
        when(userRepository.findByUsername(username)).thenReturn(Optional.empty());

        Mono<Void> result = userService.logout();

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException &&
                                     throwable.getMessage().equals("User with username: "
                                                                      + principal.getName() + " not found")
                ).verify();

        verify(userRepository, times(1)).findByUsername(username);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Успешное получение информации безопасности пользователя")
    void findSecurityInfoByUsernameSuccessTest() {
        String username = "Roman";
        UserSecurityInfo mockInfo = mock(UserSecurityInfo.class);

        when(mockInfo.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(mockInfo.getTokenVersion()).thenReturn(1);
        when(userRepository.findSecurityInfoByUserName(username)).thenReturn(Optional.of(mockInfo));

        Mono<UserSecurityInfo> result = userService.findSecurityInfoByUsername(username);

        StepVerifier.create(result)
                .expectNextMatches(info -> info.getTokenVersion().equals(1) &&
                                           info.getStatus().equals(UserStatus.ACTIVE))
                .verifyComplete();

        verify(userRepository, times(1)).findSecurityInfoByUserName(username);
    }

    @Test
    @DisplayName("Ошибка: информация безопасности не найдена по имени пользователя")
    void findSecurityInfoByUsernameNotFoundExceptionTest() {
        String username = "Unknown";

        when(userRepository.findSecurityInfoByUserName(username)).thenReturn(Optional.empty());

        Mono<UserSecurityInfo> result = userService.findSecurityInfoByUsername(username);

        StepVerifier.create(result)
                .expectErrorMatches(
                        throwable -> throwable instanceof NotFoundException &&
                                     throwable.getMessage().equals("SecurityInfo not found with username: " + username)
                ).verify();

        verify(userRepository, times(1)).findSecurityInfoByUserName(username);
    }
}
