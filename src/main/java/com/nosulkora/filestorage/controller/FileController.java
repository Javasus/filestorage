package com.nosulkora.filestorage.controller;

import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/files")
@Tag(name = "File Controller", description = "CRUD operations for files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create new file")
    public Mono<FileDto> create(@RequestBody FileDto fileDto) {
        return fileService.create(fileDto);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update file")
    public Mono<FileDto> update(@PathVariable Integer id, @RequestBody FileDto fileDto) {
        fileDto.setId(id);
        return fileService.update(fileDto);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete file")
    public Mono<Void> delete(@PathVariable Integer id) {
        return fileService.delete(id);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get file by ID")
    public Mono<FileDto> getById(@PathVariable Integer id) {
        return fileService.findById(id);
    }

    @GetMapping
    @Operation(summary = "Get all files")
    public Flux<FileDto> getAll(){
        return fileService.findAll();
    }
}
