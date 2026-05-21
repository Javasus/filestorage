package com.nosulkora.filestorage.service;

import com.nosulkora.filestorage.model.dto.UserDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UserService {
    Mono<UserDto> create(UserDto userDto);
    Mono<UserDto> update(UserDto userDto);
    Mono<Void> delete(Integer id);
    Mono<UserDto> findById(Integer id);
    Flux<UserDto> findAll();
    Mono<UserDto> findByUsername(String username);
}
