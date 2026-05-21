package com.nosulkora.filestorage.utils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class ReactiveWrapper {

    // Для Mono. Один результат
    public static <T> Mono<T> mono(Supplier<T> supplier) {
        return Mono.fromCallable(supplier::get)
                .subscribeOn(Schedulers.boundedElastic());
    }

    // Для Runnable. Void операции
    public static Mono<Void> voidOperation(Runnable runnable) {
        return Mono.fromRunnable(runnable)
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    // Для Flux. Много результатов
    public static <T> Flux<T> flux(Supplier<List<T>> supplier) {
        return Mono.fromCallable(supplier::get)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    // Для Optional. Может быть null
    public static <T> Mono<T> monoOptional(Supplier<Optional<T>> supplier) {
        return Mono.fromCallable(supplier::get)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optional -> optional.map(Mono::just).orElseGet(Mono::empty));
    }
}
