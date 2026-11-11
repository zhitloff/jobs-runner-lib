package ru.sarteam.jobs.runner.core.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Статус жизненного цикла задачи.
 *
 * <p>Задача создаётся в {@link #SCHEDULED}, захватывается в {@link #RUNNING} и завершается
 * в {@link #COMPLETED} или {@link #ERROR}.
 */
@Getter
@RequiredArgsConstructor
public enum JobStatus {

    /** Задача поставлена в очередь и ждёт запуска. */
    SCHEDULED("планируемая"),
    /** Задача выполняется. */
    RUNNING("выполняющаяся"),
    /** Задача успешно завершена. */
    COMPLETED("завершённая"),
    /** Задача завершилась с ошибкой. */
    ERROR("завершилась с ошибкой");

    /** Человекочитаемое описание статуса для логов и UI. */
    private final String description;
}
