package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.mapper.UserMapper;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.security.SecurityUtils;
import com.nosulkora.filestorage.service.UserService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Mono<UserDto> create(UserDto userDto) {
        validateUsername(userDto.getUsername());
        return checkUserDoesNotExist(userDto.getUsername())
                .then(Mono.defer(() -> {
                    User user = UserMapper.toEntity(userDto);
                    user.setStatus(UserStatus.ACTIVE);
                    user.setRole(userDto.getRole() == null ? UserRole.USER : userDto.getRole());
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
        return getCurrentUserEntity()
                .flatMap(currentUser -> getTargetUserIfExist(userDto.getId())
                        .flatMap(targetUser -> checkWriteAccess(currentUser, targetUser))
                        .flatMap(targetUser -> {
                            targetUser.setUsername(userDto.getUsername());
                            targetUser.setStatus(
                                    userDto.getStatus() != null ? userDto.getStatus() : targetUser.getStatus());

                            // Изменять роль может только ADMIN
                            if (userDto.getRole() != null && currentUser.getRole().equals(UserRole.ADMIN)) {
                                targetUser.setRole(userDto.getRole());
                            }
                            return saveUserEntity(targetUser);
                        }))
                .map(UserMapper::toDto)
                .doOnSuccess(updated -> LOGGER.info("User updated successfully: ID {}", updated.getId()));
    }

    @Override
    public Mono<Void> delete(Integer userId) {
        return getCurrentUserEntity()
                .flatMap(currentUser -> getTargetUserIfExist(userId)
                        .flatMap(targetUser -> checkWriteAccess(currentUser, targetUser)))
                .flatMap(targetUser -> ReactiveWrapper.voidOperation(() -> userRepository.deleteById(userId))
                        .doOnSuccess(v -> LOGGER.info("User deleted successfully: {} with ID: {}",
                                targetUser.getUsername(), userId)));
    }

    @Override
    public Mono<UserDto> findById(Integer userId) {
        return getCurrentUserEntity()
                .flatMap(currentUser -> getTargetUserIfExist(userId)
                        .flatMap(targetUser -> checkReadAccess(currentUser, targetUser)))
                .map(UserMapper::toDto)
                .doOnNext(dto -> LOGGER.debug("User retrieved: {} with ID: {}", dto.getUsername(), dto.getId()));
    }

    @Override
    public Flux<UserDto> findAll() {
        return getCurrentUserEntity()
                .flatMapMany(currentUser -> {
                    if (currentUser.getRole().equals(UserRole.ADMIN) ||
                        currentUser.getRole().equals(UserRole.MODERATOR)) {
                        LOGGER.info("User {} requested all users", currentUser.getUsername());
                        return ReactiveWrapper.flux(userRepository::findAll).map(UserMapper::toDto);
                    }
                    LOGGER.warn("Access denied: User {} attempted to fetch all users", currentUser.getUsername());
                    return Flux.error(new AccessDeniedException("Only MODERATOR or ADMIN can view all users"));
                });
    }

    @Override
    public Mono<UserDto> findByUsername(String username) {
        return getCurrentUserEntity()
                .flatMap(currentUser -> ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username))
                        .switchIfEmpty(Mono.error(new NotFoundException("User not found with username: " + username)))
                        .flatMap(targetUser -> checkReadAccess(currentUser, targetUser)))
                .map(UserMapper::toDto);
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

    private Mono<User> checkWriteAccess(User currentUser, User targetUser) {
        if (currentUser.getRole().equals(UserRole.ADMIN) || currentUser.getId().equals(targetUser.getId())) {
            return Mono.just(targetUser);
        }
        LOGGER.warn("Access denied: User {} attempted to modify profile of user {}",
                currentUser.getUsername(), targetUser.getId());
        return Mono.error(new AccessDeniedException("You can only modify your own profile"));
    }

    private Mono<User> checkReadAccess(User currentUser, User targetUser) {
        if (currentUser.getRole().equals(UserRole.ADMIN) ||
            currentUser.getRole().equals(UserRole.MODERATOR) ||
            currentUser.getId().equals(targetUser.getId())) {
            return Mono.just(targetUser);
        }
        LOGGER.warn("Access denied: User {} attempted to view profile of user {}",
                currentUser.getUsername(), targetUser.getId());
        return Mono.error(new AccessDeniedException("You can only view your own profile"));
    }

    private Mono<User> getCurrentUserEntity() {
        return SecurityUtils.getCurrentUserName()
                .flatMap(username -> ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username)))
                .switchIfEmpty(Mono.error(new NotFoundException("Current authenticated user not found in DB")));
    }

    private Mono<User> saveUserEntity(User user) {
        return ReactiveWrapper.mono(() -> userRepository.save(user));
    }
}
