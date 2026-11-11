package ru.sarteam.jobs.runner.core.store;

import org.junit.jupiter.api.Test;
import ru.sarteam.jobs.runner.core.io.JobsType;
import ru.sarteam.jobs.runner.core.io.ScanDirJobInput;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.model.JobStatus;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты эмуляции {@code select for update} в {@link InmemoryJobStore}:
 * блокировка задач при выборке и однократный захват задачи на выполнение.
 */
class InmemoryJobStoreTest {

    /** Тестируемое хранилище. */
    private final InmemoryJobStore jobStore = new InmemoryJobStore();

    /**
     * Проверяет, что задача остаётся залоченной до {@code tryMarkRunning}, а после захвата
     * не возвращается уже по статусу.
     */
    @Test
    void findAllWithLock_jobIsLockedUntilTryMarkRunning() {
        final Job job = addJob("Job-1");

        final List<Job> firstCall = jobStore.findAllWithLock(Instant.now(), 2);
        assertEquals(List.of(job), firstCall);

        // параллельный вызов не должен взять уже залоченую задачу
        assertTrue(jobStore.findAllWithLock(Instant.now(), 2).isEmpty());

        assertTrue(jobStore.tryMarkRunning(job.getId(), Instant.now()));
        assertEquals(JobStatus.RUNNING, job.getStatus());

        // после захвата задача не возвращается уже по статусу
        assertTrue(jobStore.findAllWithLock(Instant.now(), 2).isEmpty());
    }

    /**
     * Проверяет, что залоченная задача пропускается, а лимит добирается оставшимися задачами.
     */
    @Test
    void findAllWithLock_skipsLockedJobsAndRespectsLimit() {
        final Job first = addJob("Job-1");
        final Job second = addJob("Job-2");
        final Instant now = Instant.now();

        final List<Job> firstCall = jobStore.findAllWithLock(now, 1);
        assertEquals(1, firstCall.size());
        final Job lockedJob = firstCall.get(0);

        // залоченная задача не попадает в выборку, поэтому второй вызов отдаёт оставшуюся
        final List<Job> secondCall = jobStore.findAllWithLock(now, 2);
        assertEquals(1, secondCall.size());
        assertNotEquals(lockedJob.getId(), secondCall.get(0).getId());
        assertEquals(Set.of(first.getId(), second.getId()),
                Set.of(lockedJob.getId(), secondCall.get(0).getId()));
    }

    /**
     * Проверяет, что одну и ту же задачу нельзя захватить дважды.
     */
    @Test
    void tryMarkRunning_sameJobIsNotMarkedTwice() {
        final Job job = addJob("Job-1");
        final Instant now = Instant.now();

        assertTrue(jobStore.tryMarkRunning(job.getId(), now));
        assertFalse(jobStore.tryMarkRunning(job.getId(), now), "повторный захват одной задачи недопустим");
        assertEquals(JobStatus.RUNNING, job.getStatus());
    }

    /**
     * Добавляет в хранилище задачу сканирования директории.
     *
     * @param name имя задачи
     * @return добавленная задача
     */
    private Job addJob(String name) {
        final Job job = new Job()
                .setName(name)
                .setType(JobsType.SCAN_DIRECTORY)
                .setJobInput(new ScanDirJobInput(Path.of(".")));
        jobStore.add(job);
        return job;
    }
}
