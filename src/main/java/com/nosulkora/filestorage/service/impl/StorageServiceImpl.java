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

import java.io.InputStream;
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

        // Превращаем реактивный стрим DataBuffer в один InputStream
        return DataBufferUtils.join(filePart.content())
                .flatMap(dataBuffer -> {
                    try {
                        // 1. false - не освобождает буфер автоматически. Контролируем освобождение памяти вручную.
                        InputStream inputStream = dataBuffer.asInputStream(false);
                        // Размер файла в байтах (в буфере)
                        long contentLength = dataBuffer.readableByteCount();

                        // Выполняем блокирующую операцию на boundedElastic воркер-пуле.
                        return Mono.fromCallable(() -> {
                                    minioClient.putObject(
                                            PutObjectArgs.builder()
                                                    .bucket(bucketName)
                                                    .object(uniqueFileName)
                                                    .stream(inputStream, contentLength, -1)
                                                    .contentType(filePart.headers().getContentType().toString())
                                                    .build()
                                    );

                                    // Формируем результирующий URL расположения файла.
                                    String fileLocation = endpoint + "/" + bucketName + "/" + uniqueFileName;
                                    LOGGER.info("File uploaded to MinIO successfully: {}", fileLocation);
                                    return fileLocation;
                                })
                                .subscribeOn(Schedulers.boundedElastic())
                                // 2. Гарантированно освобождаем буфер после завершения операции
                                // (успех или ошибка внутри Callable)
                                .doFinally(signalType -> DataBufferUtils.release(dataBuffer));

                    } catch (Exception e) {
                        // Если упало до создания Mono.fromCallable, тоже очищаем память
                        DataBufferUtils.release(dataBuffer);
                        LOGGER.error("Failed to convert file part content: {}", e.getMessage());
                        return Mono.error(new ValidationException("Failed to process file upload"));
                    }
                })
                // 3. Страховка на случай, если цепочка отменилась до flatMap или во время сборки
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
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
