package com.nosulkora.filestorage.controller;

import com.nosulkora.filestorage.model.dto.AuthRequestDto;
import com.nosulkora.filestorage.model.dto.AuthResponseDto;
import com.nosulkora.filestorage.model.dto.UserDto;
import com.nosulkora.filestorage.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth Controller", description = "Authentication and public registration")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a new regular user")
    public Mono<UserDto> register(@RequestBody UserDto userDto) {
        userDto.setRole(null); // по дефолту устанавливаем USER
        return userService.create(userDto);
    }

    @PostMapping("/login")
    @Operation(summary = "Authenticate user and get JWT token")
    public Mono<AuthResponseDto> login(@RequestBody AuthRequestDto request) {
        return userService.login(request);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Logout user and invalidate all their tokens everywhere")
    public Mono<Void> logout() {
        return userService.logout();
    }
}
