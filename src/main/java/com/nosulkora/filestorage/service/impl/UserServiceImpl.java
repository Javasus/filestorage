package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.mapper.UserMapper;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.model.entity.User;
import com.nosulkora.filestorage.model.enums.UserStatus;
import com.nosulkora.filestorage.repository.UserRepository;
import com.nosulkora.filestorage.service.UserService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Mono<UserDto> create(UserDto userDto) {
        return ReactiveWrapper.mono(() -> {
                    User user = UserMapper.toEntity(userDto);
                    user.setStatus(UserStatus.ACTIVE);
                    return userRepository.save(user);
                })
                .map(UserMapper::toDto);
    }

    @Override
    public Mono<UserDto> update(UserDto userDto) {
        return ReactiveWrapper.mono(() -> {
                    User user = UserMapper.toEntity(userDto);
                    return userRepository.save(user);
                })
                .map(UserMapper::toDto);
    }

    @Override
    public Mono<Void> delete(Integer id) {
        return ReactiveWrapper.voidOperation(() -> userRepository.deleteById(id));
    }

    @Override
    public Mono<UserDto> findById(Integer id) {
        return ReactiveWrapper.monoOptional(() -> userRepository.findById(id))
                .map(UserMapper::toDto);
    }

    @Override
    public Flux<UserDto> findAll() {
        return ReactiveWrapper.flux(userRepository::findAll)
                .map(UserMapper::toDto);
    }
}
