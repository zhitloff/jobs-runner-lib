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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link InmemoryJobStore}: выборка задач без блокировки и однократный захват
 * задачи на выполнение.
 */
class InmemoryJobStoreTest {

    /** Тестируемое хранилище. */
    private final InmemoryJobStore jobStore = new InmemoryJobStore();

    /**
     * Проверяет, что выборка отдаёт только задачи в статусе {@code SCHEDULED},
     * в порядке от более старых и с учётом лимита.
     */
    @Test
    void findAllByLimit_returnsScheduledJobsAndRespectsLimit() {
        final Job first = addJob("Job-1");
        final Job second = addJob("Job-2");
        final Instant now = Instant.now();

        final List<Job> selected = jobStore.findAllByLimit(now, 10);
        assertEquals(Set.of(first.getId(), second.getId()),
                selected.stream().map(Job::getId).collect(Collectors.toSet()));

        final List<Instant> createdAt = selected.stream().map(Job::getCreatedAt).toList();
        assertEquals(createdAt.stream().sorted().toList(), createdAt, "сначала более старые");

        assertEquals(1, jobStore.findAllByLimit(now, 1).size());
        assertTrue(jobStore.findAllByLimit(now, 0).isEmpty(), "при limit <= 0 выборка пуста");
    }

    /**
     * Проверяет, что выборка не блокирует задачи: повторный вызов получает их же,
     * а от двойного запуска защищает только {@code tryMarkRunning}.
     */
    @Test
    void findAllByLimit_doesNotLockJobs() {
        final Job job = addJob("Job-1");
        final Instant now = Instant.now();

        final List<Job> firstCall = jobStore.findAllByLimit(now, 2);
        assertEquals(List.of(job), firstCall);

        // блокировки нет: параллельный вызов получает ту же задачу
        assertEquals(firstCall, jobStore.findAllByLimit(now, 2));

        assertTrue(jobStore.tryMarkRunning(job.getId(), now));
        assertEquals(JobStatus.RUNNING, job.getStatus());

        // после захвата задача не возвращается уже по статусу
        assertTrue(jobStore.findAllByLimit(now, 2).isEmpty());
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
