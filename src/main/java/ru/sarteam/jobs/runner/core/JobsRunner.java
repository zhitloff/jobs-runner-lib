package ru.sarteam.jobs.runner.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.sarteam.jobs.runner.core.handler.JobHandler;
import ru.sarteam.jobs.runner.core.io.JobInput;
import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.listener.JobResultListener;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.store.JobStore;

import java.time.Instant;
import java.util.List;

/**
 * Оркестратор запуска задач: берёт из {@link JobStore} задачи, готовые к выполнению,
 * захватывает их и выполняет каждую в отдельном виртуальном потоке.
 *
 * <p>Порядок одного запуска:
 * <ol>
 *   <li>{@link JobStore#findAllByLimit} — выборка задач без блокировки;</li>
 *   <li>{@link JobStore#tryMarkRunning} — захват задачи (защита от двойного запуска);</li>
 *   <li>{@link JobHandler#handle} — выполнение задачи;</li>
 *   <li>{@link JobStore#completeRun} — завершение задачи и (опционально) нотификация слушателей.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class JobsRunner {

    /** Обработчики задач; для одного типа задачи должен быть только один подходящий. */
    private final List<JobHandler<?, ?>> jobHandlers;
    /** Слушатели результатов, вызываемые после завершения задачи. */
    private final List<JobResultListener> jobResultListeners;
    /** Хранилище задач — точка расширения, реализуемая приложением-хостом. */
    private final JobStore jobStore;

    /**
     * Запускает пачку готовых к выполнению задач.
     *
     * <p>Метод ничего не блокирует в хранилище: параллельные вызовы могут выбрать одну и ту же
     * задачу, но выполнит её только тот, чей {@link JobStore#tryMarkRunning} вернул {@code true}.
     * Каждая задача выполняется в отдельном виртуальном потоке, поэтому управление возвращается,
     * не дожидаясь завершения задач. Задача без обработчика логируется и пропускается — пачка
     * не падает.
     *
     * @param now   текущее время, передаётся в хранилище при выборке и захвате
     * @param limit максимальное количество задач в пачке
     */
    protected void start(Instant now, int limit) {
        jobStore.findAllByLimit(now, limit).forEach(job -> {
            JobHandler<?, ?> jobHandler;
            try {
                jobHandler = findHandlerByJob(job);
            } catch (RuntimeException e) {
                // Задача без обработчика не должна останавливать запуск остальных
                log.warn("start() - jobHandler not found, job = {}", job, e);
                return;
            }
            if (!jobStore.tryMarkRunning(job.getId(), now)) {
                return;
            }
            // Виртуальные потоки не пулируются: новый поток на каждую задачу
            Thread.startVirtualThread(() -> run(jobHandler, job));
        });
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
