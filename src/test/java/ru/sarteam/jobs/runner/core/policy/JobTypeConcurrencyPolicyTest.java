package ru.sarteam.jobs.runner.core.policy;

import org.junit.jupiter.api.Test;
import ru.sarteam.jobs.runner.core.io.JobInput;
import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.model.JobType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты {@link JobTypeConcurrencyPolicy}: лимиты по типам, освобождение слотов
 * и потокобезопасность счётчиков.
 */
class JobTypeConcurrencyPolicyTest {

    /** Проверяет, что политика без лимитов пропускает любое количество задач. */
    @Test
    void unlimited_allowsAnyNumberOfJobs() {
        final JobTypeConcurrencyPolicy policy = JobTypeConcurrencyPolicy.unlimited();

        for (int i = 0; i < 100; i++) {
            assertTrue(policy.tryAcquire(TestJobType.LIMITED));
        }
        policy.release(TestJobType.LIMITED);
    }

    /** Проверяет, что лимит действует только на указанный тип задачи. */
    @Test
    void perType_limitsConfiguredTypeOnly() {
        final JobTypeConcurrencyPolicy policy =
                JobTypeConcurrencyPolicy.perType(Map.of(TestJobType.LIMITED, 1));

        assertTrue(policy.tryAcquire(TestJobType.LIMITED));
        assertFalse(policy.tryAcquire(TestJobType.LIMITED));
        // тип без лимита не ограничен
        assertTrue(policy.tryAcquire(TestJobType.UNLIMITED));
        assertTrue(policy.tryAcquire(TestJobType.UNLIMITED));
    }

    /** Проверяет, что после освобождения слот типа снова доступен. */
    @Test
    void perType_releaseReturnsSlot() {
        final JobTypeConcurrencyPolicy policy =
                JobTypeConcurrencyPolicy.perType(Map.of(TestJobType.LIMITED, 1));

        assertTrue(policy.tryAcquire(TestJobType.LIMITED));
        assertFalse(policy.tryAcquire(TestJobType.LIMITED));

        policy.release(TestJobType.LIMITED);

        assertTrue(policy.tryAcquire(TestJobType.LIMITED));
    }

    /** Проверяет, что лишний release не увеличивает лимит. */
    @Test
    void perType_extraReleaseDoesNotIncreaseLimit() {
        final JobTypeConcurrencyPolicy policy =
                JobTypeConcurrencyPolicy.perType(Map.of(TestJobType.LIMITED, 1));

        assertTrue(policy.tryAcquire(TestJobType.LIMITED));
        policy.release(TestJobType.LIMITED);
        policy.release(TestJobType.LIMITED);

        assertTrue(policy.tryAcquire(TestJobType.LIMITED));
        assertFalse(policy.tryAcquire(TestJobType.LIMITED));
    }

    /** Проверяет, что неположительный лимит отклоняется при создании политики. */
    @Test
    void perType_nonPositiveLimitRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> JobTypeConcurrencyPolicy.perType(Map.of(TestJobType.LIMITED, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> JobTypeConcurrencyPolicy.perType(Map.of(TestJobType.LIMITED, -1)));
    }

    /** Проверяет, что параллельные попытки не превышают лимит типа. */
    @Test
    void perType_parallelAcquiresDoNotExceedLimit() throws Exception {
        final int limit = 2;
        final JobTypeConcurrencyPolicy policy =
                JobTypeConcurrencyPolicy.perType(Map.of(TestJobType.LIMITED, limit));
        final List<Callable<Boolean>> attempts = IntStream.range(0, 100)
                .<Callable<Boolean>>mapToObj(i -> () -> policy.tryAcquire(TestJobType.LIMITED))
                .toList();
        final ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            int acquired = 0;
            for (Future<Boolean> future : executor.invokeAll(attempts)) {
                if (future.get(10, TimeUnit.SECONDS)) {
                    acquired++;
                }
            }
            assertEquals(limit, acquired, "параллельные попытки не должны превышать лимит типа");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Тестовый тип задачи.
     *
     * <p>Классы входа и выхода не используются — важна только идентичность константы.
     */
    private enum TestJobType implements JobType {

        /** Тип, для которого задаётся лимит. */
        LIMITED,
        /** Тип без лимита. */
        UNLIMITED;

        @Override
        public String getDescription() {
            return name();
        }

        @Override
        public Class<? extends JobInput> getJobInputClass() {
            return JobInput.class;
        }

        @Override
        public Class<? extends JobOutput> getJobOutputClass() {
            return JobOutput.class;
        }
    }
}
