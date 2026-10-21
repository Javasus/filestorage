package com.nosulkora.filestorage.service;

import com.nosulkora.filestorage.exception.AccessDeniedException;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.enums.UserRole;
import com.nosulkora.filestorage.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Проверка прав участников для доступа к файлам.
 */
@Component
public class FileSecurityChecker {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSecurityChecker.class);

    /**
     * Проверка прав участника для просмотра файла.
     * <br> Права есть только у владельца этого файла и у ADMIN & MODERATOR.
     *
     * @param file          Файл над которым совершается действие.
     * @param userPrincipal Пользователь, который совершает изменения.
     * @return Файл.
     */
    public Mono<File> checkReadAccess(File file, UserPrincipal userPrincipal) {
        if (userPrincipal.getRole().equals(UserRole.USER) && !userPrincipal.getId().equals(file.getUser().getId())) {
            LOGGER.warn("Access denied: User {} (USER) tried to read file ID {} owned by user ID {}",
                    userPrincipal.getName(), file.getId(), file.getUser().getId());
            return Mono.error(new AccessDeniedException("You do not have permission to view this file."));
        }
        return Mono.just(file);
    }

    /**
     * Проверка прав пользователя.
     * <br> Права есть только у ADMIN & MODERATOR.
     *
     * @param file          Файл над которым совершается действие.
     * @param userPrincipal Пользователь, который совершает изменения.
     * @return Файл над которым совершается действие.
     */
    public Mono<File> checkWriteAccess(File file, UserPrincipal userPrincipal) {
        if (userPrincipal.getRole().equals(UserRole.USER)) {
            LOGGER.warn("Access denied: User {} (USER) tried to modify/delete file ID {}",
                    userPrincipal.getName(), file.getId());
            return Mono.error(new AccessDeniedException("You do not have permission to work with this file."));
        }
        return Mono.just(file);
    }
}
