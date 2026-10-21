package com.nosulkora.filestorage.controller;

import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
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

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a file to S3 storage")
    public Mono<FileDto> create(@RequestPart("file") Mono<Part> filePartMono) {
        return filePartMono.flatMap(part -> {
            if (!(part instanceof FilePart filePart)) {
                return Mono.error(new ValidationException("File name cannot be empty"));
            }

            if (filePart.filename().trim().isEmpty()) {
                return Mono.error(new ValidationException("File name cannot be empty"));
            }

            FileDto fileDto = new FileDto();
            fileDto.setName(filePart.filename());
            return fileService.create(fileDto, filePart);
        });
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
    public Flux<FileDto> getAll() {
        return fileService.findAll();
    }
}
