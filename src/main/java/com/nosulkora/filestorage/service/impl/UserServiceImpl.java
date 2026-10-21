package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.mapper.UserMapper;
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
import com.nosulkora.filestorage.service.UserService;
import com.nosulkora.filestorage.service.UserValidator;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
    private final UserValidator userValidator;
    private final UserSecurityChecker userSecurityChecker;

    public UserServiceImpl(
            UserRepository userRepository,
            @Lazy PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            UserValidator userValidator,
            UserSecurityChecker userSecurityChecker
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userValidator = userValidator;
        this.userSecurityChecker = userSecurityChecker;
    }

    @Override
    public Mono<UserDto> create(UserDto userDto) {
        userValidator.validateUsername(userDto.getUsername());
        userValidator.validateUserPassword(userDto.getPassword());
        return userSecurityChecker.checkUserDoesNotExist(userDto.getUsername())
                .then(Mono.defer(() -> {
                    User user = UserMapper.toEntity(userDto);
                    user.setStatus(UserStatus.ACTIVE);
                    user.setRole(UserRole.USER);
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
        userValidator.validateUsername(userDto.getUsername());
        return SecurityUtils.getCurrentUser()
                .flatMap(currentUser -> {
                    return userSecurityChecker.validateResourceAccess(currentUser, false, List.of(UserRole.ADMIN))
                            .then(userSecurityChecker.getTargetUserIfExist(userDto.getId()))
                            .flatMap(targetUser -> userSecurityChecker.validateAccountStatusForActions(currentUser, targetUser))
                            .flatMap(targetUser -> userSecurityChecker.changeUserRoleAndFields(userDto, targetUser, currentUser))
                            .flatMap(this::saveUserEntity);
                })
                .map(UserMapper::toDto)
                .doOnSuccess(updated -> LOGGER.info("User updated successfully: ID {}", updated.getId()));
    }

    @Override
    public Mono<Void> delete(Integer userId) {
        return SecurityUtils.getCurrentUser()
                .flatMap(currentUser -> {
                    return userSecurityChecker.validateResourceAccess(currentUser, false, List.of(UserRole.ADMIN))
                            .then(userSecurityChecker.getTargetUserIfExist(userId))
                            .flatMap(targetUser -> {
                                if (userSecurityChecker.isAccountBlocked(targetUser)) {
                                    return Mono.empty();
                                }
                                targetUser.setStatus(UserStatus.BLOCKED);
                                targetUser.setTokenVersion(targetUser.getTokenVersion() + 1);
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
                    boolean isOwner = isOwner(currentUser, userId);
                    return userSecurityChecker.validateResourceAccess(currentUser, isOwner, List.of(UserRole.ADMIN, UserRole.MODERATOR))
                            .then(userSecurityChecker.getTargetUserIfExist(userId))
                            .flatMap(targetUser -> userSecurityChecker.validateAccountStatusForProfile(currentUser, targetUser))
                            .map(UserMapper::toDto);
                })
                .doOnNext(dto -> LOGGER.debug("User retrieved: {} with ID: {}", dto.getUsername(), dto.getId()));
    }

    @Override
    public Flux<UserDto> findAll() {
        return SecurityUtils.getCurrentUser()
                .flatMapMany(currentUser -> {
                    // false - так как получать список могу только ADMIN & MODERATOR
                    return userSecurityChecker.validateResourceAccess(
                                    currentUser,
                                    false,
                                    List.of(UserRole.ADMIN, UserRole.MODERATOR))
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
                    boolean isOwner = isOwnerByUsername(currentUser, username);
                    return userSecurityChecker.validateResourceAccess(currentUser, isOwner, List.of(UserRole.ADMIN, UserRole.MODERATOR))
                            .then(ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username)))
                            .switchIfEmpty(Mono.error(new NotFoundException("User not found with username: " + username)))
                            .flatMap(targetUser -> userSecurityChecker.validateAccountStatusForProfile(currentUser, targetUser))
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
                            user.getStatus().name(),
                            user.getTokenVersion());
                    return Mono.just(new AuthResponseDto(token));
                });
    }

    @Override
    public Mono<Void> logout() {
        return SecurityUtils.getCurrentUser()
                .flatMap(currentUser ->
                        ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(currentUser.getName()))
                                .switchIfEmpty(Mono.error(
                                        new NotFoundException("User with username: "
                                                              + currentUser.getName() + " not found")))
                                .flatMap(user -> {
                                    // Инкрементируем версию токена
                                    user.setTokenVersion(user.getTokenVersion() + 1);
                                    return ReactiveWrapper.voidOperation(() -> userRepository.save(user));
                                })
                );
    }

    @Override
    public Mono<UserSecurityInfo> findSecurityInfoByUsername(String username) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findSecurityInfoByUserName(username))
                .switchIfEmpty(Mono.error(new NotFoundException("SecurityInfo not found with username: " + username)));
    }

    // --- Вспомогательные методы ---

    private Mono<User> saveUserEntity(User user) {
        return ReactiveWrapper.mono(() -> userRepository.save(user));
    }

    // Атомарные предикаты

    private boolean isOwner(UserPrincipal currentUser, Integer targetUserId) {
        return currentUser.getId().equals(targetUserId);
    }

    private boolean isOwnerByUsername(UserPrincipal currentUser, String targetUsername) {
        return currentUser.getName().equals(targetUsername);
    }
}