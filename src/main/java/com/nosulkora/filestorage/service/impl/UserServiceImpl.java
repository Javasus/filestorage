package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.mapper.UserMapper;
import com.nosulkora.filestorage.model.dto.AuthRequestDto;
import com.nosulkora.filestorage.model.dto.AuthResponseDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.security.JwtTokenProvider;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.security.UserPrincipal;
import com.nosulkora.filestorage.service.UserService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public UserServiceImpl(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public Mono<UserDto> create(UserDto userDto) {
        validateUsername(userDto.getUsername());
        return checkUserDoesNotExist(userDto.getUsername())
                .then(Mono.defer(() -> {
                    User user = UserMapper.toEntity(userDto);
                    user.setStatus(UserStatus.ACTIVE);
                    user.setRole(userDto.getRole() == null ? UserRole.USER : userDto.getRole());
                    // Хэшируем пароль перед сохранением.
                    user.setPasswordHash(passwordEncoder.encode(userDto.getPassword()));
                    return saveUserEntity(user);
                }))
                .map(UserMapper::toDto)
                .doOnSuccess(savedUser -> LOGGER.info(
                        "User created successfully: {} with ID: {}",
                        savedUser.getUsername(),
                        savedUser.getId()));
    }

    @Override
    public Mono<UserDto> update(UserDto userDto) {
        validateUsername(userDto.getUsername());
        return SecurityUtils.getCurrentUser()
                .flatMap(currentUser -> {
                    boolean isOwner = currentUser.getId().equals(userDto.getId());
                    return checkReadAndWriteAccess(currentUser, isOwner, List.of(UserRole.ADMIN))
                            .then(getTargetUserIfExist(userDto.getId()))
                            .flatMap(targetUser -> changeUserRoleAndFields(userDto, targetUser, currentUser))
                            .flatMap(this::saveUserEntity);
                })
                .map(UserMapper::toDto)
                .doOnSuccess(updated -> LOGGER.info("User updated successfully: ID {}", updated.getId()));
    }

    @Override
    public Mono<Void> delete(Integer userId) {
        return SecurityUtils.getCurrentUser()
                .flatMap(currentUser -> {
                    boolean isOwner = currentUser.getId().equals(userId);
                    return checkReadAndWriteAccess(currentUser, isOwner, List.of(UserRole.ADMIN))
                            .then(getTargetUserIfExist(userId))
                            .flatMap(targetUser -> {
                                if (targetUser.getStatus() == UserStatus.BLOCKED) {
                                    return Mono.empty();
                                }
                                targetUser.setStatus(UserStatus.BLOCKED);
                                return saveUserEntity(targetUser)
                                        .doOnSuccess(savedUser -> LOGGER.info(
                                                "User profile softly deleted (BLOCKED): {} with ID: {}",
                                                savedUser.getUsername(),
                                                userId));
                            });
                })
                .then();
    }

    @Override
    public Mono<UserDto> findById(Integer userId) {
        return SecurityUtils.getCurrentUser()
                .flatMap(currentUser -> {
                    boolean isOwner = currentUser.getId().equals(userId);
                    return checkReadAndWriteAccess(currentUser, isOwner, List.of(UserRole.ADMIN, UserRole.MODERATOR))
                            .then(getTargetUserIfExist(userId))
                            .map(UserMapper::toDto);
                })
                .doOnNext(dto -> LOGGER.debug("User retrieved: {} with ID: {}", dto.getUsername(), dto.getId()));
    }

    @Override
    public Flux<UserDto> findAll() {
        return SecurityUtils.getCurrentUser()
                .flatMapMany(currentUser -> {
                    // false - так как получать список могу только ADMIN & MODERATOR
                    return checkReadAndWriteAccess(currentUser, false, List.of(UserRole.ADMIN, UserRole.MODERATOR))
                            .thenMany(Flux.defer(() -> {
                                LOGGER.info("User {} requested all users", currentUser.getName());
                                return ReactiveWrapper.flux(userRepository::findAll).map(UserMapper::toDto);
                            }));
                });
    }

    @Override
    public Mono<UserDto> findByUsername(String username) {
        return SecurityUtils.getCurrentUser()
                .flatMap(currentUser -> {
                    boolean isOwner = currentUser.getName().equals(username);
                    return checkReadAndWriteAccess(currentUser, isOwner, List.of(UserRole.ADMIN, UserRole.MODERATOR))
                            .then(ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username)))
                            .switchIfEmpty(Mono.error(new NotFoundException("User not found with username: " + username)))
                            .map(UserMapper::toDto);
                });
    }

    @Override
    public Mono<AuthResponseDto> login(AuthRequestDto authRequestDto) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(authRequestDto.getUsername()))
                .switchIfEmpty(Mono.error(new AccessDeniedException("Invalid username or password")))
                .flatMap(user -> {
                    // Проверяем статус пользователя
                    if (user.getStatus() == UserStatus.BLOCKED) {
                        return Mono.error(new AccessDeniedException("User account is blocked"));
                    }
                    // Сверяем пришедший пароль с хэшем из БД
                    if (!passwordEncoder.matches(authRequestDto.getPassword(), user.getPasswordHash())) {
                        LOGGER.warn("Failed login attempt for user: {}", authRequestDto.getUsername());
                        return Mono.error(new AccessDeniedException("Invalid username or password"));
                    }
                    // Генерируем JWT токен
                    String token = jwtTokenProvider.createToken(
                            user.getId(),
                            user.getUsername(),
                            user.getRole().name(),
                            user.getStatus().name());
                    return Mono.just(new AuthResponseDto(token));
                });
    }

    // --- Вспомогательные методы ---

    private void validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            LOGGER.error("Validation failed: Username cannot be empty");
            throw new ValidationException("Username cannot be empty");
        }
    }

    private Mono<Void> checkUserDoesNotExist(String username) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username))
                .flatMap(user -> Mono.<Void>error(
                        new ValidationException("User with username " + username + " already exists")))
                .switchIfEmpty(Mono.empty());
    }

    private Mono<User> getTargetUserIfExist(Integer userId) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findById(userId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found with ID: " + userId)));
    }

    /**
     * Проверяет, что у пользователя есть права на изменение/удаление/получение данных пользователя без запроса в БД.
     * <br> 1. Проверяет, что ID currentUser совпадает с ID пользователя по которому выполняется запрос.
     * <br> 2. Проверяет, что роль currentUser позволяет выполнять запрошенное действие.
     *
     * @param currentUser  Текущий пользователь, который совершает запрос.
     * @param isOwner      Флаг, который отвечает на вопрос. currentUser - это пользователь по которому совершается запрос?
     * @param allowedRoles Список ролей который допускается до выполнения запроса.
     * @return Mono<User>
     */
    private Mono<User> checkReadAndWriteAccess(
            UserPrincipal currentUser,
            boolean isOwner,
            List<UserRole> allowedRoles
    ) {
        if (isOwner || allowedRoles.contains(currentUser.getRole())) {
            return Mono.empty(); // Права подтверждены, продолжаем цепочку
        }
        LOGGER.warn("Access denied: User {} with role {} attempted unauthorized access",
                currentUser.getName(), currentUser.getRole());
        return Mono.error(new AccessDeniedException("You do not have permission to perform this action."));
    }

    private Mono<User> saveUserEntity(User user) {
        return ReactiveWrapper.mono(() -> userRepository.save(user));
    }

    /**
     * Проверяет права на изменение роли и обновляет поля сущности USER перед сохранением.
     */
    private Mono<User> changeUserRoleAndFields(UserDto userDto, User targetUser, UserPrincipal currentUser) {
        if (userDto.getRole() != null && !userDto.getRole().equals(targetUser.getRole())) {
            if (!currentUser.getRole().equals(UserRole.ADMIN)) {
                LOGGER.warn(
                        "Access denied: User {} attempted to change role to {}",
                        currentUser.getName(),
                        userDto.getRole());
                return Mono.error(new AccessDeniedException("Only ADMIN can change user roles."));
            }
            // Если это ADMIN — разрешаем смену роли
            targetUser.setRole(userDto.getRole());
        }

        // Обновляем остальные текстовые поля и статус
        targetUser.setUsername(userDto.getUsername());
        targetUser.setStatus(userDto.getStatus() != null ? userDto.getStatus() : targetUser.getStatus());

        return Mono.just(targetUser);
    }
}
