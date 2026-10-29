package com.nosulkora.filestorage.component;

import com.nosulkora.filestorage.service.impl.StorageServiceImpl;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class StorageServiceImplTest {

    @Mock
    private MinioClient minioClient;

    @InjectMocks
    private StorageServiceImpl storageService;

    private MockedStatic<DataBufferUtils> mockedDataBufferUtils;
    private MockedStatic<Files> mockedFiles;

    @BeforeEach
    void setUp() {
        // Открываем статические моки
        mockedDataBufferUtils = Mockito.mockStatic(DataBufferUtils.class);
        mockedFiles = Mockito.mockStatic(Files.class);

        ReflectionTestUtils.setField(storageService, "bucketName", "filestorage");
        ReflectionTestUtils.setField(storageService, "endpoint", "http://localhost:9000");
    }

    @AfterEach
    void tearDown() {
        // закрываем статические моки
        mockedDataBufferUtils.close();
        mockedFiles.close();
    }

    @Test
    @DisplayName("Проверка стриминга файла: метод не использует  join(), а пишет потоково на диск.")
    void uploadFileStreamsToDiskWithoutJoinSuccessTest() throws Exception {
        // 1. Настраиваем тестовые данные
        FilePart filePart = mock(FilePart.class);
        when(filePart.filename()).thenReturn("test_large_file.zip");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        when(filePart.headers()).thenReturn(headers);

        Flux<DataBuffer> mockContent = Flux.empty();
        when(filePart.content()).thenReturn(mockContent);

        Path mockTempPath = mock(Path.class);

        // 2. Настраиваем статический мок для Files. Эмулируем создание временного файла и его размер.
        mockedFiles.when(() -> Files.createTempFile(anyString(), anyString())).thenReturn(mockTempPath);
        mockedFiles.when(() -> Files.size(mockTempPath)).thenReturn(100L);
        mockedFiles.when(() -> Files.newInputStream(mockTempPath)).thenReturn(mock(InputStream.class));
        mockedFiles.when(() -> Files.deleteIfExists(mockTempPath)).thenReturn(true);

        Mono<String> result = storageService.uploadFile(filePart);

        StepVerifier.create(result)
                .expectNextCount(1)
                .verifyComplete();

        mockedDataBufferUtils.verify(() -> DataBufferUtils.join(any()), never());
        verify(minioClient, times(1)).putObject(any(PutObjectArgs.class));
    }
}
