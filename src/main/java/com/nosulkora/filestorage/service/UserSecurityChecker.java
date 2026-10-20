package com.nosulkora.filestorage.service;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.exception.NotFoundException;
import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.model.enums.UserStatus;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.security.UserPrincipal;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class UserSecurityChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserSecurityChecker.class);
    private final UserRepository userRepository;

    public UserSecurityChecker(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    private boolean hasPrivilegedRole(UserPrincipal currentUser, List<UserRole> allowedRoles) {
        return allowedRoles.contains(currentUser.getRole());
    }

    public boolean isAccountBlocked(User targetUser) {
        return targetUser.getStatus() == (UserStatus.BLOCKED);
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
    public Mono<Void> validateResourceAccess(
            UserPrincipal currentUser,
            boolean isOwner,
            List<UserRole> allowedRoles
    ) {
        if (isOwner || hasPrivilegedRole(currentUser, allowedRoles)) {
            return Mono.empty();
        }
        LOGGER.warn("Access denied: User {} with role {} attempted unauthorized access",
                currentUser.getName(), currentUser.getRole());
        return Mono.error(new AccessDeniedException("You do not have permission to perform this action."));
    }

    /**
     * Контролирует доступ к профилю. Заблокированный пользователь не может читать себя,
     * но ADMIN и MODERATOR могут просматривать его карточку.
     */
    public Mono<User> validateAccountStatusForProfile(UserPrincipal currentUser, User targetUser) {
        if (isAccountBlocked(targetUser)) {
            if (hasPrivilegedRole(currentUser, List.of(UserRole.ADMIN, UserRole.MODERATOR))) {
                return Mono.just(targetUser); // Администрации доступ к заблокированным разрешен
            }
            LOGGER.warn("Access denied: Blocked user {} attempted to view profile", targetUser.getUsername());
            return Mono.error(new AccessDeniedException("User account is blocked. Access denied."));
        }
        return Mono.just(targetUser);
    }

    /**
     * Контролирует мутирующие действия. Заблокированный аккаунт нельзя изменять никому, кроме ADMIN.
     */
    public Mono<User> validateAccountStatusForActions(UserPrincipal currentUser, User targetUser) {
        if (isAccountBlocked(targetUser)) {
            if (hasPrivilegedRole(currentUser, List.of(UserRole.ADMIN))) {
                return Mono.just(targetUser); // Только ADMIN может редактировать заблокированных
            }
            LOGGER.warn("Access denied: Attempted modification of blocked account {}", targetUser.getUsername());
            return Mono.error(new AccessDeniedException("User account is blocked. Access denied."));
        }
        return Mono.just(targetUser);
    }

    /**
     * Проверяет права на изменение роли и обновляет поля сущности USER перед сохранением.
     *
     * @param userDto     Новые данные пользователя полученные в запросе.
     * @param targetUser  Пользователь данные которого будут изменены.
     * @param currentUser Пользователь, который меняет данные. (Может быть ADMIN)
     */
    public Mono<User> changeUserRoleAndFields(UserDto userDto, User targetUser, UserPrincipal currentUser) {
        boolean roleChanged = userDto.getRole() != null && !userDto.getRole().equals(targetUser.getRole());
        boolean statusChanged = userDto.getStatus() != null && !userDto.getStatus().equals(targetUser.getStatus());

        // Если меняем только имя пользователя, то сразу его меняем.
        if (!roleChanged && !statusChanged) {
            targetUser.setUsername(userDto.getUsername());
            return Mono.just(targetUser);
        }

        // Если меняем Роль или Статус тогда проверяем роль меняющего, если ADMIN тогда меняем данные.
        return validateResourceAccess(currentUser, false, List.of(UserRole.ADMIN))
                .then(Mono.fromSupplier(() -> {
                    if (roleChanged) {
                        targetUser.setRole(userDto.getRole());
                    }
                    if (statusChanged) {
                        targetUser.setStatus(userDto.getStatus());
                    }

                    // инкрементируем версию токена
                    targetUser.setTokenVersion(targetUser.getTokenVersion() + 1);
                    LOGGER.info("Token version incremented for user ID {}: critical data changed by ADMIN"
                            , targetUser.getId());

                    targetUser.setUsername(userDto.getUsername());
                    return targetUser;
                }));
    }

    // ----------------------- Работа с БД. -----------------------

    public Mono<Void> checkUserDoesNotExist(String username) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findByUsername(username))
                .flatMap(user -> Mono.<Void>error(
                        new ValidationException("User with username " + username + " already exists")))
                .switchIfEmpty(Mono.empty());
    }

    public Mono<User> getTargetUserIfExist(Integer userId) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findById(userId))
                .switchIfEmpty(Mono.error(new NotFoundException("User not found with ID: " + userId)));
    }

    // ------------------------- Вспомогательные методы-----------------------

    private Mono<User> validateUserRole(UserDto userDto, UserPrincipal currentUser) {
        if (!currentUser.getRole().equals(UserRole.ADMIN)) {
            LOGGER.warn(
                    "Access denied: User {} attempted to change role to {}",
                    currentUser.getName(),
                    userDto.getRole());
            return Mono.error(new AccessDeniedException("Only ADMIN can change user roles."));
        }
        return Mono.empty();
    }
}
