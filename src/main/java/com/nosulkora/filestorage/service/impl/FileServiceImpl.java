package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.mapper.FileMapper;
import com.nosulkora.filestorage.model.dto.FileDto;
import com.nosulkora.filestorage.model.entity.File;
import com.nosulkora.filestorage.model.enums.FileStatus;
import com.nosulkora.filestorage.repository.FileRepository;
import com.nosulkora.filestorage.service.FileService;
import com.nosulkora.filestorage.utils.ReactiveWrapper;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class FileServiceImpl implements FileService {

    private final FileRepository fileRepository;

    public FileServiceImpl(FileRepository fileRepository) {
        this.fileRepository = fileRepository;
    }

    @Override
    public Mono<FileDto> create(FileDto fileDto) {
        return ReactiveWrapper.mono(() -> {
                    File file = FileMapper.toEntity(fileDto);
                    file.setStatus(FileStatus.ACTIVE);
                    return fileRepository.save(file);
                })
                .map(FileMapper::toDto);
    }

    @Override
    public Mono<FileDto> update(FileDto fileDto) {
        return ReactiveWrapper.mono(() -> {
                    File file = FileMapper.toEntity(fileDto);
                    return fileRepository.save(file);
                })
                .map(FileMapper::toDto);
    }

    @Override
    public Mono<Void> delete(Integer id) {
        return ReactiveWrapper.voidOperation(() -> fileRepository.deleteById(id));
    }

    @Override
    public Mono<FileDto> findById(Integer id) {
        return ReactiveWrapper.monoOptional(() -> fileRepository.findById(id))
                .map(FileMapper::toDto);
    }

    @Override
    public Flux<FileDto> findAll() {
        return ReactiveWrapper.flux(fileRepository::findAll)
                .map(FileMapper::toDto);
    }
}
