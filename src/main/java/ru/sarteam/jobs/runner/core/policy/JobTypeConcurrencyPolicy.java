package ru.sarteam.jobs.runner.core.policy;

import ru.sarteam.jobs.runner.core.model.JobType;

import java.util.Map;

/**
 * Политика параллельности в разрезе типа задачи.
 *
 * <p>Позволяет ограничить количество одновременно выполняемых задач отдельного типа
 * независимо от общего лимита виртуальных потоков. Политика спрашивается перед
 * {@code JobStore.tryMarkRunning}: при отказе задача остаётся в статусе {@code SCHEDULED}
 * и не занимает слот потока, поэтому её заберёт следующий вызов {@code start}.
 *
 * <p>По умолчанию используется {@link #unlimited()}: типы задач не ограничены.
 * Реализация должна быть потокобезопасной, потому что {@link #tryAcquire} и
 * {@link #release} вызываются из потоков планировщика и виртуальных потоков задач.
 *
 * <p>Ключом служит сам {@link JobType}, поэтому реализации типов должны корректно
 * реализовывать {@code equals}/{@code hashCode} (enum'ы это делают по идентичности).
 */
public interface JobTypeConcurrencyPolicy {

    /**
     * Пытается занять слот типа задачи.
     *
     * @param jobType тип задачи
     * @return {@code true}, если слот занят; {@code false}, если лимит типа исчерпан
     */
    boolean tryAcquire(JobType jobType);

    /**
     * Освобождает слот типа задачи.
     *
     * <p>Вызывается только после успешного {@link #tryAcquire} и ровно один раз.
     *
     * @param jobType тип задачи
     */
    void release(JobType jobType);

    /**
     * Политика без ограничений: любой тип задачи запускается без лимита.
     *
     * @return политика без лимитов
     */
    static JobTypeConcurrencyPolicy unlimited() {
        return PerTypeConcurrencyPolicy.UNLIMITED;
    }

    /**
     * Политика с лимитом одновременно выполняемых задач на каждый указанный тип.
     *
     * <p>Типы, которых нет в {@code limits}, не ограничены. Значение лимита —
     * максимальное количество одновременно выполняемых задач этого типа.
     *
     * @param limits лимиты по типам задач; значения должны быть положительными
     * @return политика с лимитами по типам
     * @throws IllegalArgumentException если лимит не положительный
     * @throws NullPointerException     если карта, тип или лимит равны {@code null}
     */
    static JobTypeConcurrencyPolicy perType(Map<? extends JobType, Integer> limits) {
        return PerTypeConcurrencyPolicy.of(limits);
    }
}
