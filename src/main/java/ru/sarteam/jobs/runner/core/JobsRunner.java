package ru.sarteam.jobs.runner.core;

import lombok.extern.slf4j.Slf4j;
import ru.sarteam.jobs.runner.core.handler.JobHandler;
import ru.sarteam.jobs.runner.core.io.JobInput;
import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.listener.JobResultListener;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.policy.JobTypeConcurrencyPolicy;
import ru.sarteam.jobs.runner.core.store.JobStore;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Оркестратор запуска задач: берёт из {@link JobStore} задачи, готовые к выполнению,
 * захватывает их и выполняет каждую в отдельном виртуальном потоке.
 *
 * <p>Порядок одного запуска:
 * <ol>
 *   <li>{@link JobStore#findAllByLimit} — выборка задач без блокировки;</li>
 *   <li>{@link JobTypeConcurrencyPolicy#tryAcquire} — слот типа задачи;</li>
 *   <li>{@link JobStore#tryMarkRunning} — захват задачи (защита от двойного запуска);</li>
 *   <li>{@link JobHandler#handle} — выполнение задачи;</li>
 *   <li>{@link JobStore#completeRun} — завершение задачи и (опционально) нотификация слушателей.</li>
 * </ol>
 */
@Slf4j
public class JobsRunner {

    /** Обработчики задач; для одного типа задачи должен быть только один подходящий. */
    private final List<JobHandler<?, ?>> jobHandlers;
    /** Слушатели результатов, вызываемые после завершения задачи. */
    private final List<JobResultListener> jobResultListeners;
    /** Хранилище задач — точка расширения, реализуемая приложением-хостом. */
    private final JobStore jobStore;
    /** Политика лимитов по типу задачи; по умолчанию — без лимита. */
    private final JobTypeConcurrencyPolicy jobTypeConcurrencyPolicy;
    /**
     * Занятые слоты под виртуальные потоки: работающие задачи плюс зарезервированные,
     * но ещё не запущенные. Счётчик живёт между вызовами {@link #start}, поэтому лимит
     * соблюдается и при параллельных запусках.
     */
    private final AtomicInteger runningThreadCount = new AtomicInteger();

    /**
     * Создаёт оркестратор без лимитов по типам задач.
     *
     * @param jobHandlers        обработчики задач
     * @param jobResultListeners слушатели результатов
     * @param jobStore           хранилище задач
     */
    public JobsRunner(List<JobHandler<?, ?>> jobHandlers,
                      List<JobResultListener> jobResultListeners,
                      JobStore jobStore) {
        this(jobHandlers, jobResultListeners, jobStore, JobTypeConcurrencyPolicy.unlimited());
    }

    /**
     * Создаёт оркестратор с политикой лимитов по типам задач.
     *
     * @param jobHandlers              обработчики задач
     * @param jobResultListeners       слушатели результатов
     * @param jobStore                 хранилище задач
     * @param jobTypeConcurrencyPolicy политика лимитов по типу задачи
     */
    public JobsRunner(List<JobHandler<?, ?>> jobHandlers,
                      List<JobResultListener> jobResultListeners,
                      JobStore jobStore,
                      JobTypeConcurrencyPolicy jobTypeConcurrencyPolicy) {
        this.jobHandlers = jobHandlers;
        this.jobResultListeners = jobResultListeners;
        this.jobStore = jobStore;
        this.jobTypeConcurrencyPolicy = jobTypeConcurrencyPolicy;
    }

    /**
     * Запускает пачку готовых к выполнению задач без ограничения количества потоков.
     *
     * <p>Эквивалентно {@code start(now, jobLimit, Integer.MAX_VALUE)}.
     *
     * @param now      текущее время, передаётся в хранилище при выборке и захвате
     * @param jobLimit максимальное количество задач в пачке
     */
    protected void start(Instant now, int jobLimit) {
        start(now, jobLimit, Integer.MAX_VALUE);
    }

    /**
     * Запускает пачку готовых к выполнению задач, не превышая лимит одновременных виртуальных потоков.
     *
     * <p>Метод ничего не блокирует в хранилище: параллельные вызовы могут выбрать одну и ту же
     * задачу, но выполнит её только тот, чей {@link JobStore#tryMarkRunning} вернул {@code true}.
     * Каждая задача выполняется в отдельном виртуальном потоке, поэтому управление возвращается,
     * не дожидаясь завершения задач. Задача без обработчика логируется и пропускается — пачка
     * не падает.
     *
     * <p>Лимит {@code threadCountMax} считается по занятым слотам: работающий поток плюс слот,
     * зарезервированный под задачу, которую ещё предстоит запустить. Если свободных слотов нет,
     * {@link JobStore#findAllByLimit} не вызывается вовсе — выборка не тратится впустую.
     * Значение {@code threadCountMax <= 0} означает «свободных слотов нет», поэтому {@code start}
     * ничего не запускает (как и {@code jobLimit <= 0}).
     *
     * <p>Дополнительно {@link JobTypeConcurrencyPolicy} ограничивает количество одновременных
     * задач отдельного типа. Слот типа занимается перед {@link JobStore#tryMarkRunning}; если
     * лимит типа исчерпан, задача не захватывается, остаётся в {@code SCHEDULED} и не занимает
     * слот потока — её заберёт следующий вызов {@code start}.
     *
     * @param now            текущее время, передаётся в хранилище при выборке и захвате
     * @param jobLimit       максимальное количество задач в пачке
     * @param threadCountMax максимальное количество одновременно выполняемых задач
     */
    protected void start(Instant now, int jobLimit, int threadCountMax) {
        final int running = runningThreadCount.get();
        if (running >= threadCountMax) {
            // Свободных слотов нет: хранилище не опрашиваем
            log.debug("start() - threadCountMax reached, running = {}, threadCountMax = {}", running, threadCountMax);
            return;
        }
        // Просим у хранилища не больше задач, чем есть свободных слотов
        final int available = Math.min(jobLimit, threadCountMax - running);
        for (Job job : jobStore.findAllByLimit(now, available)) {
            JobHandler<?, ?> jobHandler;
            try {
                jobHandler = findHandlerByJob(job);
            } catch (RuntimeException e) {
                // Задача без обработчика не должна останавливать запуск остальных
                log.warn("start() - jobHandler not found, job = {}", job, e);
                continue;
            }
            // Сначала лимит типа: при отказе задача остаётся SCHEDULED и слот потока не занимает
            if (!jobTypeConcurrencyPolicy.tryAcquire(job.getType())) {
                log.debug("start() - jobType limit reached, job = {} skipped, type = {}",
                        job.getId(), job.getType());
                continue;
            }
            // Слот потока занимаем до захвата задачи: иначе можно пометить задачу RUNNING и не запустить поток
            if (!tryAcquireSlot(threadCountMax)) {
                // Задача не будет запущена — слот типа нужно вернуть
                jobTypeConcurrencyPolicy.release(job.getType());
                log.debug("start() - threadCountMax reached, job = {} skipped", job.getId());
                break;
            }
            final boolean marked;
            try {
                marked = jobStore.tryMarkRunning(job.getId(), now);
            } catch (RuntimeException e) {
                // Задача не захвачена: занятые слоты нужно вернуть, иначе они «потекут»
                releaseJobSlots(job);
                throw e;
            }
            if (!marked) {
                // Задачу захватил параллельный вызов — слоты не нужны
                releaseJobSlots(job);
                continue;
            }
            // Виртуальные потоки не пулируются: новый поток на каждую задачу
            Thread.startVirtualThread(() -> {
                try {
                    run(jobHandler, job);
                } finally {
                    // Слоты свободны только после завершения задачи: лимиты действуют на всё выполнение
                    releaseJobSlots(job);
                }
            });
        }
    }

    /**
     * Освобождает слоты задачи: общий слот потока и слот типа задачи.
     *
     * @param job задача
     */
    private void releaseJobSlots(Job job) {
        releaseSlot();
        jobTypeConcurrencyPolicy.release(job.getType());
    }

    /**
     * Атомарно занимает один слот под виртуальный поток.
     *
     * @param threadCountMax максимальное количество одновременно выполняемых задач
     * @return {@code true}, если слот занят; {@code false}, если лимит исчерпан
     */
    private boolean tryAcquireSlot(int threadCountMax) {
        // getAndUpdate вернёт значение до изменения: инкремент был, только если слот был свободен
        return runningThreadCount.getAndUpdate(
                current -> current < threadCountMax ? current + 1 : current) < threadCountMax;
    }

    /** Освобождает слот, занятый под виртуальный поток. */
    private void releaseSlot() {
        runningThreadCount.decrementAndGet();
    }

    /**
     * Выполняет задачу и завершает её в хранилище.
     *
     * <p>Исключение из {@link JobHandler#handle} превращается в {@link JobResult#failure}.
     * Если {@code job.isCompleteAndNotifyTogether()} равно {@code true}, нотификация идёт
     * внутри {@link JobStore#completeRun} (outbox-семантика, одна транзакция), иначе —
     * после завершения и по состоянию, возвращённому хранилищем.
     *
     * @param handler обработчик задачи
     * @param job     задача
     */
    private void run(JobHandler<?, ?> handler, Job job) {
        JobResult<? extends JobOutput> jobResult;
        try {
            jobResult = handle(handler, job.getJobInput());
        } catch (RuntimeException e) {
            jobResult = JobResult.failure(e);
        }
        final JobResult<? extends JobOutput> finalJobResult = jobResult;
        if (job.isCompleteAndNotifyTogether()) {
            // Выполнение завершения задачи и нотификация (если через OUTBOX) в одной транзакции
            jobStore.completeRun(job.getId(), finalJobResult, completedJob -> notifyResult(completedJob, finalJobResult));
        } else {
            // Выполнение завершения задачи и нотификация раздельно, по обновлённому состоянию
            Job completedJob = jobStore.completeRun(job.getId(), finalJobResult, updatedJob -> {});
            notifyResult(completedJob, finalJobResult);
        }
    }

    /**
     * Приводит вход задачи к типу обработчика.
     *
     * <p>{@link Job} не параметризован {@link JobHandler}, поэтому статически связать тип задачи
     * и тип входа нельзя: это единственное место с непроверяемым приведением.
     *
     * @param handler  обработчик задачи
     * @param jobInput вход задачи в нетипизированном виде
     * @param <I>      тип входа обработчика
     * @param <O>      тип выхода обработчика
     * @return результат выполнения обработчика
     */
    @SuppressWarnings("unchecked")
    private static <I extends JobInput, O extends JobOutput> JobResult<? extends JobOutput> handle(
            JobHandler<I, O> handler, JobInput jobInput) {
        return handler.handle((I) jobInput);
    }

    /**
     * Ищет обработчик по типу задачи (первое совпадение {@link JobHandler#supports}).
     *
     * @param job задача
     * @return подходящий обработчик
     * @throws IllegalStateException если обработчик для типа задачи не найден
     */
    private JobHandler<?, ?> findHandlerByJob(Job job) {
        return jobHandlers.stream()
                .filter(handler -> handler.supports(job.getType()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("JobHandler not found for job = " + job));
    }

    /**
     * Уведомляет слушателей о результате задачи.
     *
     * <p>Слушатели вызываются по первому совпадению {@link JobResultListener#supports};
     * исключение из слушателя только логируется и не влияет на другие слушатели и задачу.
     *
     * @param job       актуальное состояние задачи
     * @param jobResult результат выполнения
     */
    private void notifyResult(Job job, JobResult<? extends JobOutput> jobResult) {
        jobResultListeners.stream()
                .filter(listener -> listener.supports(job.getType()))
                .forEach(listener -> {
                    try {
                        switch (jobResult) {
                            case JobResult.Success<?> success -> listener.onSuccess(job, success);
                            case JobResult.Failure<?> failure -> listener.onFailure(job, failure);
                        }
                    } catch (RuntimeException e) {
                        log.warn("JobResultListener = {} failed for job = {}", listener.getClass().getName(), job, e);
                    }
                });
    }
}
