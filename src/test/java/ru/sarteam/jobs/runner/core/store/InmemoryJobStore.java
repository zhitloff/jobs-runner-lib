package ru.sarteam.jobs.runner.core.store;

import lombok.extern.slf4j.Slf4j;
import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.model.JobStatus;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory реализация {@link JobStore} для тестов и примеров.
 *
 * <p>Хранит задачи в {@link ConcurrentHashMap} и меняет тот же инстанс {@link Job},
 * который был добавлен в хранилище, поэтому тест может наблюдать статус по своей ссылке.
 * Выборка задачи не блокирует: от двойного запуска защищает атомарный переход
 * {@code SCHEDULED -> RUNNING} в {@link #tryMarkRunning}.
 *
 * <p>Не транзакционна и не предназначена для production.
 */
@Slf4j
public class InmemoryJobStore implements JobStore {

    /** Задачи по идентификатору. */
    private final Map<UUID, Job> jobMap = new ConcurrentHashMap<>();

    /**
     * Сохраняет задачу, проставляя id, время создания и статус {@code SCHEDULED}.
     *
     * @param job задача; {@code null} игнорируется
     */
    @Override
    public void add(Job job) {
        Optional.ofNullable(job)
                .map(this::prepareJob)
                .ifPresent(j -> jobMap.put(j.getId(), j));
    }

    /**
     * Сохраняет пачку задач, проставляя каждой id, время создания и статус {@code SCHEDULED}.
     *
     * @param jobs задачи; {@code null}-коллекция и {@code null}-элементы игнорируются
     */
    @Override
    public void addAll(Collection<Job> jobs) {
        if (jobs != null && !jobs.isEmpty()) {
            jobMap.putAll(jobs.stream()
                    .filter(Objects::nonNull)
                    .map(this::prepareJob)
                    .collect(Collectors.toMap(Job::getId, Function.identity())));
        }
    }

    /**
     * Выбирает задачи в статусе {@code SCHEDULED}, не блокируя их.
     *
     * <p>Задачу могут получить несколько параллельных вызовов — это нормально:
     * выполнит её только тот, кто успешно захватит её в {@link #tryMarkRunning}.
     *
     * @param now   текущее время (в in-memory реализации не используется)
     * @param limit максимальное количество задач в выборке
     * @return задачи в стабильном порядке: сначала более старые
     */
    @Override
    public List<Job> findAllByLimit(Instant now, int limit) {
        if (limit <= 0) return List.of();
        // с защитой от null и стабильным порядком
        return jobMap.values().stream()
                .filter(job -> JobStatus.SCHEDULED.equals(job.getStatus()))
                .sorted(Comparator
                        .comparing(Job::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Job::getId))
                .limit(limit)
                .toList();
    }

    /**
     * Захватывает задачу: переводит её из {@code SCHEDULED} в {@code RUNNING}.
     *
     * <p>Переход выполняется атомарно и только один раз, поэтому повторный или
     * параллельный вызов вернёт {@code false}. Захваченная задача дальше не попадает
     * в выборку уже по статусу — блокировка при выборке не нужна.
     *
     * @param jobId идентификатор задачи
     * @param now   время начала выполнения
     * @return {@code true}, если задача захвачена именно этим вызовом
     */
    @Override
    public boolean tryMarkRunning(UUID jobId, Instant now) {
        final AtomicBoolean marked = new AtomicBoolean(false);
        jobMap.computeIfPresent(jobId, (id, job) -> {
            // захватить можно только задачу в SCHEDULED: параллельный вызов не должен её перехватить
            if (JobStatus.SCHEDULED.equals(job.getStatus())) {
                job.setStatus(JobStatus.RUNNING);
                job.setStartedAt(now);
                marked.set(true);
            }
            return job;
        });
        return marked.get();
    }

    /**
     * Завершает задачу: обогащает её результатом и выполняет callback.
     *
     * @param jobId       идентификатор задачи
     * @param jobResult   результат выполнения
     * @param consumerJob callback в той же «транзакции», что и завершение
     * @return актуальное состояние задачи (тот же инстанс, что лежит в хранилище)
     * @throws RuntimeException если задача с таким идентификатором не найдена
     */
    @Override
    public Job completeRun(UUID jobId, JobResult<? extends JobOutput> jobResult, Consumer<Job> consumerJob) {
        Job job = jobMap.get(jobId);
        if (job != null) {
            this.enrichJobByResult(job, jobResult);
            consumerJob.accept(job);
            return job;
        }
        throw new RuntimeException("Job not fund by id = " + jobId);
    }

    /**
     * Готовит задачу к хранению: проставляет id, время создания и статус {@code SCHEDULED}.
     *
     * @param job задача
     * @return та же задача с заполненными служебными полями
     */
    // todo: может быть и этот метод вынести в интерфейс
    private Job prepareJob(Job job) {
        return job
                .setId((job.getId() != null) ? job.getId() : UUID.randomUUID())
                .setCreatedAt(Instant.now())
                .setStatus(JobStatus.SCHEDULED);
    }
}
