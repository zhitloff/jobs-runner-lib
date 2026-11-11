package ru.sarteam.jobs.runner.core.store;

import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.model.JobStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Хранилище задач — единственная точка расширения, которую реализует приложение-хост
 * (БД, in-memory, очередь и т.п.).
 *
 * <p>Методы задают семантику, которую реализация обязана соблюсти:
 * {@link #findAllByLimit} — выборка готовых задач без блокировки (одну задачу могут получить
 * несколько параллельных вызовов), {@link #tryMarkRunning} — атомарный захват (единственная
 * защита от двойного запуска), {@link #completeRun} — завершение задачи и callback
 * в одной транзакции.
 */
public interface JobStore {

    /**
     * Сохраняет задачу, перезаписывая существующую с тем же идентификатором.
     *
     * @param job задача; {@code null} игнорируется
     */
    void add(Job job);

    /**
     * Сохраняет пачку задач, перезаписывая существующие по идентификатору.
     *
     * @param jobs задачи; {@code null}-коллекция и {@code null}-элементы игнорируются
     */
    void addAll(Collection<Job> jobs);

    /**
     * Выбирает задачи, готовые к выполнению, не блокируя их.
     *
     * <p>Блокировка не требуется: параллельные вызовы могут получить одну и ту же задачу,
     * от двойного запуска защищает {@link #tryMarkRunning}.
     *
     * @param now   текущее время
     * @param limit максимальное количество задач в выборке
     * @return готовые к выполнению задачи в стабильном порядке (сначала более старые)
     */
    List<Job> findAllByLimit(Instant now, int limit);

    /**
     * Захватывает задачу на выполнение (защита от двойного запуска).
     *
     * @param jobId идентификатор задачи
     * @param now   текущее время
     * @return {@code true}, если задача захвачена именно этим вызовом; {@code false} — если
     * задача не найдена или уже захвачена другим вызовом
     */
    boolean tryMarkRunning(UUID jobId, Instant now);

    /**
     * Завершает задачу и выполняет callback в той же транзакции.
     *
     * <p>Возвращает актуальное состояние задачи: это может быть новый инстанс, поэтому
     * дальше нужно использовать возвращённое значение, а не переданный ранее инстанс.
     *
     * @param jobId       идентификатор задачи
     * @param jobResult   результат выполнения
     * @param consumerJob callback в той же транзакции, что и завершение (например, нотификация)
     * @return актуальное состояние задачи
     */
    Job completeRun(UUID jobId, JobResult<? extends JobOutput> jobResult, Consumer<Job> consumerJob);

    /**
     * Обогащение задачи после получения результата.
     *
     * <p>Проставляет статус ({@code COMPLETED} / {@code ERROR}), выход или текст ошибки
     * и время завершения.
     *
     * @param job       задача
     * @param jobResult результат выполнения
     */
    default void enrichJobByResult(Job job, JobResult<? extends JobOutput> jobResult) {
        switch (jobResult) {
            case JobResult.Success<?> success -> {
                job.setStatus(JobStatus.COMPLETED);
                job.setJobOutput(success.jobOutput());
            }
            case JobResult.Failure<?> failure -> {
                job.setStatus(JobStatus.ERROR);
                job.setError(failure.toString());
            }
        }
        job.setStoppedAt(Instant.now());
    }
}
