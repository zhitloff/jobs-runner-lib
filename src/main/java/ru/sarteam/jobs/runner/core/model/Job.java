package ru.sarteam.jobs.runner.core.model;

import lombok.Data;
import lombok.experimental.Accessors;
import ru.sarteam.jobs.runner.core.io.JobInput;
import ru.sarteam.jobs.runner.core.io.JobOutput;

import java.time.Instant;
import java.util.UUID;

/**
 * Задача планировщика: что выполнить ({@code type}), с какими данными
 * ({@code jobInput}), в каком состоянии ({@code status}) и с каким результатом.
 *
 * <p>Модель мутабельная: хранилище вправе менять тот же инстанс и возвращать его как
 * актуальное состояние задачи. Время {@code createdAt} проставляет хранилище при добавлении.
 */
@Data
@Accessors(chain = true)
public class Job {

    /** Идентификатор задачи. */
    private UUID id; // todo: заменить на дженерик
    /** Человекочитаемое имя задачи. */
    private String name;
    /** Тип задачи: определяет обработчик и слушателей. */
    private JobType type;
    /** Текущий статус задачи. */
    private JobStatus status;
    /** Вход задачи — данные, с которыми работает обработчик. */
    private JobInput jobInput;
    /** Выход задачи — результат успешного выполнения. */
    private JobOutput jobOutput;
    /** Текст ошибки последнего выполнения. */
    private String error;
    /**
     * Признак outbox-семантики: если {@code true}, завершение задачи и нотификация
     * слушателей выполняются в одной транзакции {@code JobStore.completeRun}.
     */
    private boolean completeAndNotifyTogether;
    /** Время постановки задачи в очередь. */
    private Instant createdAt;
    /** Время захвата задачи на выполнение. */
    private Instant startedAt;
    /** Время завершения задачи. */
    private Instant stoppedAt;
    // todo: придумать процент выполнения
}
