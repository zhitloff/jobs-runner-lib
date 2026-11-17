package ru.sarteam.jobs.runner.core;

import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.sarteam.jobs.runner.core.handler.JobHandler;
import ru.sarteam.jobs.runner.core.handler.ScanDirJobHandler;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.io.JobsType;
import ru.sarteam.jobs.runner.core.io.ScanDirJobInput;
import ru.sarteam.jobs.runner.core.io.ScanDirJobOutput;
import ru.sarteam.jobs.runner.core.listener.JobResultListener;
import ru.sarteam.jobs.runner.core.listener.ScanDirJobResultListener;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.model.JobStatus;
import ru.sarteam.jobs.runner.core.model.JobType;
import ru.sarteam.jobs.runner.core.policy.JobTypeConcurrencyPolicy;
import ru.sarteam.jobs.runner.core.store.InmemoryJobStore;
import ru.sarteam.jobs.runner.core.store.JobStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Сквозные тесты {@link JobsRunner}: одиночный запуск задачи, защита от двойного
 * выполнения при параллельных вызовах {@code start()} и лимит одновременных потоков.
 */
class JobsRunnerTest {

    /** Хранилище задач для тестов. */
    private final JobStore inmemoryJobStore = new InmemoryJobStore();
    /** Тестируемый оркестратор. */
    private JobsRunner jobsRunner;

    /**
     * Собирает оркестратор с обработчиком и слушателем для задач сканирования директории.
     */
    @BeforeEach
    void setUp() {
        final JobHandler<ScanDirJobInput, ScanDirJobOutput> scanDirJobHandler = new ScanDirJobHandler();
        final JobResultListener jobResultListener = new ScanDirJobResultListener();
        jobsRunner = new JobsRunner(List.of(scanDirJobHandler), List.of(jobResultListener), inmemoryJobStore);
    }

    /**
     * Выполняет задачу сканирования и проверяет, что выход содержит найденный файл.
     *
     * @param tempDir временная директория с файлом для сканирования
     */
    @Test
    @SneakyThrows
    void start(@TempDir Path tempDir) {
        final Path file = Files.writeString(tempDir.resolve("file.txt"), "test");
        final Job job = buildJob("Job-1", new ScanDirJobInput(tempDir));
        inmemoryJobStore.add(job);

        jobsRunner.start(Instant.now(), 2);

        // start() только запускает виртуальный поток: без ожидания тест завершится раньше задачи
        awaitStatus(job, JobStatus.COMPLETED, Duration.ofSeconds(10));

        final ScanDirJobOutput jobOutput = assertInstanceOf(ScanDirJobOutput.class, job.getJobOutput());
        assertTrue(jobOutput.getPathFiles().contains(file.toAbsolutePath().toString()),
                () -> "jobOutput не содержит " + file + ": " + jobOutput);
    }

    /**
     * Проверяет, что одновременные вызовы {@code start()} не запускают одну задачу дважды.
     *
     * @param tempDir временная директория с файлом для сканирования
     */
    @Test
    @SneakyThrows
    void start_parallelCalls_doNotRunJobTwice(@TempDir Path tempDir) {
        final Path file = Files.writeString(tempDir.resolve("file.txt"), "test");
        final AtomicInteger handleCalls = new AtomicInteger();
        final JobHandler<ScanDirJobInput, ScanDirJobOutput> slowHandler = new JobHandler<>() {
            @Override
            public boolean supports(JobType jobType) {
                return JobsType.SCAN_DIRECTORY.equals(jobType);
            }

            @Override
            public JobResult<ScanDirJobOutput> handle(ScanDirJobInput jobInput) {
                handleCalls.incrementAndGet();
                try {
                    // расширяем окно, в котором задача уже запущена
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return JobResult.success(new ScanDirJobOutput(List.of(file.toString())));
            }
        };
        final JobsRunner parallelJobsRunner = new JobsRunner(
                List.of(slowHandler), List.of(new ScanDirJobResultListener()), inmemoryJobStore);
        final Job job = buildJob("Job-1", new ScanDirJobInput(tempDir));
        inmemoryJobStore.add(job);

        // оба start() видят одну и ту же задачу: второй должен отсечься на tryMarkRunning
        final int threadCount = 2;
        final CyclicBarrier barrier = new CyclicBarrier(threadCount);
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            final List<Callable<Void>> starts = IntStream.range(0, threadCount)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        barrier.await();
                        parallelJobsRunner.start(Instant.now(), 2);
                        return null;
                    })
                    .toList();
            for (Future<Void> future : executor.invokeAll(starts)) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        awaitStatus(job, JobStatus.COMPLETED, Duration.ofSeconds(10));
        assertEquals(1, handleCalls.get(), "при параллельном start() задача не должна выполняться дважды");
    }

    /**
     * Проверяет, что {@code threadCountMax} ограничивает одновременные виртуальные потоки,
     * а при исчерпании лимита {@code start()} не опрашивает хранилище.
     *
     * @param tempDir временная директория с файлом для сканирования
     */
    @Test
    @SneakyThrows
    void start_threadCountMax_limitsConcurrentThreads(@TempDir Path tempDir) {
        final Path file = Files.writeString(tempDir.resolve("file.txt"), "test");
        final int jobsCount = 3;
        final int threadCountMax = 1;
        final AtomicInteger findAllCalls = new AtomicInteger();
        final AtomicInteger handleCalls = new AtomicInteger();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        // считаем обращения к хранилищу: при достигнутом лимите их быть не должно
        final JobStore countingJobStore = new InmemoryJobStore() {
            @Override
            public List<Job> findAllByLimit(Instant now, int limit) {
                findAllCalls.incrementAndGet();
                return super.findAllByLimit(now, limit);
            }
        };
        final JobHandler<ScanDirJobInput, ScanDirJobOutput> blockingHandler = new JobHandler<>() {
            @Override
            public boolean supports(JobType jobType) {
                return JobsType.SCAN_DIRECTORY.equals(jobType);
            }

            @Override
            public JobResult<ScanDirJobOutput> handle(ScanDirJobInput jobInput) {
                handleCalls.incrementAndGet();
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                firstStarted.countDown();
                try {
                    // держим слот занятым, пока тест проверяет лимит
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrent.decrementAndGet();
                }
                return JobResult.success(new ScanDirJobOutput(List.of(file.toString())));
            }
        };
        final JobsRunner limitedJobsRunner = new JobsRunner(
                List.of(blockingHandler), List.of(new ScanDirJobResultListener()), countingJobStore);
        final List<Job> jobs = IntStream.rangeClosed(1, jobsCount)
                .mapToObj(i -> buildJob("Job-" + i, new ScanDirJobInput(tempDir)))
                .toList();
        jobs.forEach(countingJobStore::add);

        limitedJobsRunner.start(Instant.now(), jobsCount, threadCountMax);

        assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "первая задача должна стартовать");
        // первый поток ещё занят: повторный start() не должен даже опрашивать хранилище
        final int findAllCallsBefore = findAllCalls.get();
        limitedJobsRunner.start(Instant.now(), jobsCount, threadCountMax);
        assertEquals(findAllCallsBefore, findAllCalls.get(),
                "при достигнутом лимите start() не должен вызывать jobStore.findAllByLimit()");
        assertEquals(threadCountMax, handleCalls.get(), "повторный start() не должен запускать задачи");

        release.countDown();
        // слот освобождается после завершения задачи, поэтому опрашиваем раннер, как это делает планировщик
        awaitAllCompleted(limitedJobsRunner, jobs, jobsCount, threadCountMax);

        assertEquals(threadCountMax, maxConcurrent.get(),
                "одновременно не должно выполняться больше задач, чем threadCountMax");
        assertEquals(jobsCount, handleCalls.get());
    }

    /**
     * Проверяет, что лимит по типу задачи оставляет лишнюю задачу в {@code SCHEDULED},
     * не занимает слот потока и освобождается после завершения выполняющейся задачи.
     *
     * @param tempDir временная директория с файлом для сканирования
     */
    @Test
    @SneakyThrows
    void start_jobTypeLimit_keepsJobScheduledAndReleasesSlot(@TempDir Path tempDir) {
        final Path file = Files.writeString(tempDir.resolve("file.txt"), "test");
        final AtomicInteger handleCalls = new AtomicInteger();
        final AtomicInteger concurrent = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        final JobHandler<ScanDirJobInput, ScanDirJobOutput> blockingHandler = new JobHandler<>() {
            @Override
            public boolean supports(JobType jobType) {
                return JobsType.SCAN_DIRECTORY.equals(jobType);
            }

            @Override
            public JobResult<ScanDirJobOutput> handle(ScanDirJobInput jobInput) {
                handleCalls.incrementAndGet();
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                firstStarted.countDown();
                try {
                    // держим слот типа занятым, пока тест проверяет лимит
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    concurrent.decrementAndGet();
                }
                return JobResult.success(new ScanDirJobOutput(List.of(file.toString())));
            }
        };
        // лимит типа 1: вторая задача того же типа не должна запуститься одновременно с первой
        final JobsRunner typeLimitedJobsRunner = new JobsRunner(
                List.of(blockingHandler),
                List.of(new ScanDirJobResultListener()),
                inmemoryJobStore,
                JobTypeConcurrencyPolicy.perType(Map.of(JobsType.SCAN_DIRECTORY, 1)));
        final List<Job> jobs = List.of(
                buildJob("Job-1", new ScanDirJobInput(tempDir)),
                buildJob("Job-2", new ScanDirJobInput(tempDir)));
        inmemoryJobStore.addAll(jobs);

        typeLimitedJobsRunner.start(Instant.now(), jobs.size(), 2);

        assertTrue(firstStarted.await(10, TimeUnit.SECONDS), "первая задача должна стартовать");
        assertEquals(1, handleCalls.get(), "при лимите типа должна выполняться только одна задача");
        assertEquals(1, jobs.stream()
                        .filter(job -> JobStatus.SCHEDULED.equals(job.getStatus()))
                        .count(),
                "задача сверх лимита типа должна остаться в SCHEDULED");

        release.countDown();
        // слот освобождается в finally виртуального потока, поэтому следующая задача запустится
        awaitAllCompleted(typeLimitedJobsRunner, jobs, jobs.size(), 2);

        assertEquals(jobs.size(), handleCalls.get(), "после освобождения слота должны выполниться обе задачи");
        assertEquals(1, maxConcurrent.get(), "одновременно не должно выполняться больше задач, чем лимит типа");
    }

    /**
     * Ждёт, пока все задачи перейдут в {@link JobStatus#COMPLETED}, периодически вызывая
     * {@code start()} (слоты освобождаются только после завершения задач).
     *
     * @param jobsRunner     тестируемый оркестратор
     * @param jobs           задачи
     * @param jobLimit       размер пачки для {@code start()}
     * @param threadCountMax лимит одновременных потоков
     * @throws InterruptedException если ожидание прервано
     */
    private static void awaitAllCompleted(JobsRunner jobsRunner, List<Job> jobs, int jobLimit, int threadCountMax)
            throws InterruptedException {
        final Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (jobs.stream().anyMatch(job -> !JobStatus.COMPLETED.equals(job.getStatus()))) {
            if (Instant.now().isAfter(deadline)) {
                fail("не все задачи завершились за 10 секунд: " + jobs);
            }
            jobsRunner.start(Instant.now(), jobLimit, threadCountMax);
            Thread.sleep(50);
        }
    }

    /**
     * Ждёт, пока задача перейдёт в ожидаемый статус.
     *
     * <p>Задачи выполняются в виртуальных потоках, поэтому тесту нужно дождаться результата
     * опросом, а не полагаться на порядок выполнения.
     *
     * @param job     задача
     * @param expected ожидаемый статус
     * @param timeout максимальное время ожидания
     * @throws InterruptedException если ожидание прервано
     */
    private static void awaitStatus(Job job, JobStatus expected, Duration timeout) throws InterruptedException {
        final Instant deadline = Instant.now().plus(timeout);
        while (!expected.equals(job.getStatus())) {
            if (Instant.now().isAfter(deadline)) {
                fail("Job не достиг статуса %s за %s: status = %s, error = %s"
                        .formatted(expected, timeout, job.getStatus(), job.getError()));
            }
            Thread.sleep(50);
        }
    }

    /**
     * Собирает задачу сканирования директории.
     *
     * @param name     имя задачи
     * @param jobInput вход задачи
     * @return задача с типом {@link JobsType#SCAN_DIRECTORY}
     */
    private Job buildJob(String name, ScanDirJobInput jobInput) {
        return new Job()
                .setName(name)
                .setType(JobsType.SCAN_DIRECTORY)
                .setJobInput(jobInput);


    }
}
