package com.nosulkora.filestorage.service.impl;

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
        validateFilename(userDto.getUsername());

        return checkUserNotExist(userDto.getUsername())
                .then(ReactiveWrapper.mono(() -> {
                            User user = UserMapper.toEntity(userDto);
                            user.setStatus(UserStatus.ACTIVE);
                            user.setRole(userDto.getRole() == null ? UserRole.USER : userDto.getRole());
                            User savedUser = userRepository.save(user);
                            LOGGER.info(
                                    "User created: {} with ID: {} and role: {}",
                                    savedUser.getUsername(),
                                    savedUser.getId(),
                                    savedUser.getRole());
                            return savedUser;
                        })
                        .map(UserMapper::toDto));
    }

    @Override
    public Mono<UserDto> update(UserDto userDto) {
        return SecurityUtils.getCurrentUserName()
                .flatMap(currentUserName -> hasAccessToUser(currentUserName, userDto.getId())
                        .flatMap(hasAccess -> {
                            if (!hasAccess) {
                                LOGGER.warn("User {} attempted to update profile of user {}",
                                        currentUserName, userDto.getId());
                                return Mono.error(new ValidationException("You can only update your own profile"));
                            }
                            return ReactiveWrapper.monoOptional(() -> userRepository.findById(userDto.getId()))
                                    .switchIfEmpty(Mono.error(
                                            new NotFoundException("User not found by ID:" + userDto.getId())))
                                    .flatMap(existingUser -> {
                                        existingUser.setUsername(userDto.getUsername());
                                        existingUser.setStatus(
                                                userDto.getStatus() != null ? userDto.getStatus() : existingUser.getStatus());
                                        if (userDto.getRole() != null) {
                                            return getUserRole(currentUserName)
                                                    .flatMap(role -> {
                                                        if (role.equals("ADMIN")) {
                                                            existingUser.setRole(userDto.getRole());
                                                        }
                                                        return ReactiveWrapper.mono(() ->
                                                                userRepository.save(existingUser));
                                                    });
                                        }
                                        return ReactiveWrapper.mono(() -> userRepository.save(existingUser));
                                    })
                                    .map(UserMapper::toDto)
                                    .doOnSuccess(update -> LOGGER.info(
                                            "User updated: {} with ID: {}",
                                            update.getUsername(),
                                            update.getId()));
                        }));
    }

    @Override
    public Mono<Void> delete(Integer userId) {
        return SecurityUtils.getCurrentUserName()
                .flatMap(currentUserName -> hasAccessToUser(currentUserName, userId)
                        .flatMap(hasAccess -> {
                            if (!hasAccess) {
                                LOGGER.warn("User {} is trying to delete the user with id: {}",
                                        currentUserName, userId);
                                return Mono.error(new ValidationException("You can only delete your own profile"));
                            }
                            return ReactiveWrapper.mono(() -> {
                                User user = userRepository.findById(userId)
                                        .orElseThrow(() -> new NotFoundException("User not found with ID: " + userId));
                                userRepository.deleteById(userId);
                                LOGGER.info("User deleted: {} with ID: {}", user.getUsername(), userId);
                                return user;
                            }).then();
                        }));
    }

    @Override
    public Mono<UserDto> findById(Integer userId) {
        return SecurityUtils.getCurrentUserName()
                .flatMap(currentUserName -> canReadUser(currentUserName, userId)
                        .flatMap(canRead -> {
                            if (!canRead) {
                                LOGGER.warn("User {} is trying to get data about user with ID: {}",
                                        currentUserName, userId);
                                return Mono.error(new ValidationException("You can only view yor own profile"));
                            }
                            return ReactiveWrapper.monoOptional(() -> userRepository.findById(userId))
                                    .switchIfEmpty(Mono.error(
                                            new NotFoundException("User not found with ID: " + userId)))
                                    .map(UserMapper::toDto)
                                    .doOnNext(userDto -> LOGGER.info("User found: {} with ID: {}",
                                            userDto.getUsername(), userDto.getId()));
                        }));
    }

    @Override
    public Flux<UserDto> findAll() {
        return SecurityUtils.getCurrentUserName()
                .flatMapMany(currentUserName -> canReadAllUsers(currentUserName)
                        .flatMapMany(canRead -> {
                            if (!canRead) {
                                LOGGER.warn("User {} is trying to get all users", currentUserName);
                                return Mono.error(new ValidationException("Only MODERATOR or ADMIN can view all users"));
                            }
                            LOGGER.info("User {} requested all users", currentUserName);
                            return ReactiveWrapper.flux(userRepository::findAll).map(UserMapper::toDto);
                        }));

    }

    @Override
    public Mono<UserDto> findByUsername(String username) {
        return SecurityUtils.getCurrentUserName()
                .flatMap(currentUserName -> {
                    return ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username))
                            .switchIfEmpty(Mono.error(new NotFoundException("User not found with username: " + username)))
                            .flatMap(user -> {
                                return canReadUser(currentUserName, user.getId())
                                        .flatMap(canRead -> {
                                            if (!canRead) {
                                                return Mono.error(new ValidationException("You can only view your own profile"));
                                            }
                                            return Mono.just(UserMapper.toDto(user));
                                        });
                            });
                });
    }

    // --- Вспомогательные методы ---

    private void validateFilename(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new ValidationException("File name cannot be empty");
        }
    }

    private Mono<Void> checkUserNotExist(String username) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username))
                .flatMap(existingUser -> {
                    if (existingUser != null) {
                        return Mono.error(
                                new ValidationException("User with username " + username + " already exists"));
                    }
                    return Mono.empty();
                });
    }

    private Mono<Boolean> hasAccessToUser(String username, Integer userId) {
        return getUserRole(username)
                .flatMap(role -> {
                    if (role.equals(UserRole.ADMIN)) {
                        return Mono.just(true);
                    }
                    return ReactiveWrapper.monoOptional(() -> userRepository.findById(userId))
                            .map(targetUser -> targetUser.getUsername().equals(username))
                            .defaultIfEmpty(false);
                });
    }

    private Mono<UserRole> getUserRole(String username) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username))
                .map(User::getRole)
                .defaultIfEmpty(UserRole.USER);
    }

    private Mono<Boolean> canReadUser(String currentUserName, Integer userId) {
        return getUserRole(currentUserName)
                .flatMap(role -> {
                    if (role.equals(UserRole.ADMIN) || role.equals(UserRole.MODERATOR)) {
                        return Mono.just(true);
                    }
                    return ReactiveWrapper.monoOptional(() -> userRepository.findById(userId))
                            .map(targetUser -> targetUser.getUsername().equals(currentUserName))
                            .defaultIfEmpty(false);
                });
    }

    private Mono<Boolean> canReadAllUsers(String currentUserName) {
        return getUserRole(currentUserName)
                .map(role -> role.equals(UserRole.ADMIN) || role.equals(UserRole.MODERATOR));
    }
}
