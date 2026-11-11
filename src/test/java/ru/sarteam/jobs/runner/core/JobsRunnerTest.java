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
import ru.sarteam.jobs.runner.core.store.InmemoryJobStore;
import ru.sarteam.jobs.runner.core.store.JobStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Сквозные тесты {@link JobsRunner}: одиночный запуск задачи и защита от двойного
 * выполнения при параллельных вызовах {@code start()}.
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
