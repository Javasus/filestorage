package com.nosulkora.filestorage.service;

import com.nosulkora.filestorage.model.dto.FileDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface FileService {
    Mono<FileDto> create(FileDto fileDto);
    Mono<FileDto> update(FileDto fileDto);
    Mono<Void> delete(Integer id);
    Mono<FileDto> findById(Integer id);
    Flux<FileDto> findAll();
}
