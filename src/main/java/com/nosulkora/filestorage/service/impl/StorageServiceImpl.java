package com.nosulkora.filestorage.service.impl;

import com.nosulkora.filestorage.exception.ValidationException;
import com.nosulkora.filestorage.service.StorageService;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.UUID;

@Service
public class StorageServiceImpl implements StorageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StorageServiceImpl.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket-name}")
    private String bucketName;

    @Value("${minio.endpoint}")
    private String endpoint;

    public StorageServiceImpl(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public Mono<String> uploadFile(FilePart filePart) {
        // Генерируем уникальное имя файла в S3, что бы избежать перезаписи при одинаковых именах.
        String uniqueFileName = UUID.randomUUID() + "_" + filePart.filename();

        // 1. Создаем временный файл на диске ОС
        return Mono.fromCallable(() -> Files.createTempFile("minio_upload_", ".tmp"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(tempFilePart -> {
                    // 2. Потоково (streaming) пишем чанки из сети на диск, RAM не переполняется
                    return DataBufferUtils.write(filePart.content(), tempFilePart)
                            .then(Mono.fromCallable(() -> {
                                long contentLength = Files.size(tempFilePart);
                                LOGGER.debug("File streamed to temporary disk storage. Size: {} bytes", contentLength);

                                // 3. Открываем стрим из временного файла и отправляем в MinIO
                                try (InputStream inputStream = Files.newInputStream(tempFilePart)) {
                                    minioClient.putObject(
                                            PutObjectArgs.builder()
                                                    .bucket(bucketName)
                                                    .object(uniqueFileName)
                                                    .stream(inputStream, contentLength, -1)
                                                    .contentType(filePart.headers().getContentType().toString())
                                                    .build()
                                    );
                                }
                                // Формируем результирующий URL расположения файла.
                                String fileLocation = endpoint + "/" + bucketName + "/" + uniqueFileName;
                                LOGGER.info("File uploaded to MinIO successfully: {}", fileLocation);
                                return fileLocation;
                            }))
                            .subscribeOn(Schedulers.boundedElastic())
                            .doFinally(signalType -> {
                                try {
                                    boolean delete = Files.deleteIfExists(tempFilePart);
                                    if (delete) {
                                        LOGGER.debug("Temporary upload file deleted: {}", tempFilePart);
                                    }
                                } catch (IOException e) {
                                    LOGGER.error("Failed to delete temporary file: {}", tempFilePart, e);
                                }
                            });
                })
                .onErrorResume(e -> {
                    LOGGER.error("Failed to process file upload: {}", e.getMessage(), e);
                    return Mono.error(new ValidationException("Failed to process file upload"));
                });
}


    @Override
    public Mono<Void> deleteFile(String fileLocation) {
        return Mono.fromRunnable(() -> {
            try {
                if (fileLocation == null || !fileLocation.contains("/")) {
                    LOGGER.error("Invalid file location provided for deletion: {}", fileLocation);
                    return;
                }

                // Извлекаем имя объекта (ключ) из URL.
                String objectName = fileLocation.substring(fileLocation.lastIndexOf("/") + 1);

                minioClient.removeObject(
                        RemoveObjectArgs.builder()
                                .bucket(bucketName)
                                .object(objectName)
                                .build()
                );
                LOGGER.info("File deleted from MinIO successfully: {}", objectName);

            } catch (Exception e) {
                // Только логируем ошибку как ERROR. Не пробрасываем exception дальше, чтобы сбой в S3
                // не заблокировал успешное завершение удаления записи из БД.
                LOGGER.error("Failed to delete file from MinIO: {}", e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
