package com.nosulkora.filestorage.controller;

import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/users")
@Tag(name = "User Controller", description = "CRUD operations for users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create new user")
    public Mono<UserDto> create(@RequestBody UserDto userDto) {
        return userService.create(userDto);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update user")
    public Mono<UserDto> update(@PathVariable Integer id, @RequestBody UserDto userDto) {
        userDto.setId(id);
        return userService.update(userDto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete user")
    public Mono<Void> delete(@PathVariable Integer id) {
        return userService.delete(id);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get user by ID")
    public Mono<UserDto> getById(@PathVariable Integer id) {
        return userService.findById(id);
    }

    @GetMapping
    @Operation(summary = "Get all users")
    public Flux<UserDto> getAll() {
        return userService.findAll();
    }
}
