package ru.sarteam.jobs.runner.core.io;

import ru.sarteam.jobs.runner.core.listener.JobResultListener;

import java.util.Objects;

/**
 * Итог выполнения задачи: успех с результатом либо ошибка.
 *
 * <p>Дополнительную обработку результата выполняют не сам результат, а
 * слушатели {@link JobResultListener}.
 *
 * @param <O> тип выхода задачи
 */
public sealed interface JobResult<O extends JobOutput> {

    /**
     * Создаёт успешный результат.
     *
     * @param jobOutput выход задачи, не {@code null}
     * @param <O>       тип выхода задачи
     * @return успешный результат
     * @throws NullPointerException если {@code jobOutput} равен {@code null}
     */
    static <O extends JobOutput> JobResult<O> success(O jobOutput) {
        return new Success<>(Objects.requireNonNull(jobOutput, "jobOutput"));
    }

    /**
     * Создаёт результат с ошибкой.
     *
     * @param error причина ошибки, не {@code null}
     * @param <O>   тип выхода задачи
     * @return результат с ошибкой
     * @throws NullPointerException если {@code error} равен {@code null}
     */
    static <O extends JobOutput> JobResult<O> failure(Throwable error) {
        return new Failure<>(Objects.requireNonNull(error, "error"));
    }

    /**
     * Успешное выполнение задачи.
     *
     * @param jobOutput выход задачи
     * @param <O>       тип выхода задачи
     */
    record Success<O extends JobOutput>(O jobOutput) implements JobResult<O> {}

    /**
     * Выполнение задачи с ошибкой.
     *
     * @param error причина ошибки
     * @param <O>   тип выхода задачи
     */
    record Failure<O extends JobOutput>(Throwable error) implements JobResult<O> {

        /**
         * Проверяет, что причина ошибки задана.
         *
         * @throws NullPointerException если {@code error} равен {@code null}
         */
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }
}
