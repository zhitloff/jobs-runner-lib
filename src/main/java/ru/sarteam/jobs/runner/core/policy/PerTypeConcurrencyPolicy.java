package ru.sarteam.jobs.runner.core.policy;

import ru.sarteam.jobs.runner.core.model.JobType;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Политика лимитов по типам задач: у каждого ограниченного типа свой счётчик занятых слотов.
 *
 * <p>Счётчик живёт в экземпляре политики, поэтому лимит действует в рамках одного
 * {@code JobsRunner} и не является общим для нескольких экземпляров или инстансов приложения.
 */
final class PerTypeConcurrencyPolicy implements JobTypeConcurrencyPolicy {

    /** Политика без лимитов; состояния нет, поэтому экземпляр общий. */
    static final PerTypeConcurrencyPolicy UNLIMITED = new PerTypeConcurrencyPolicy(Map.of());

    /** Слоты по типам задач; типов без лимита здесь нет. */
    private final Map<JobType, TypeSlot> slots;

    private PerTypeConcurrencyPolicy(Map<JobType, Integer> limits) {
        final Map<JobType, TypeSlot> typeSlots = new HashMap<>();
        limits.forEach((jobType, limit) -> typeSlots.put(jobType, new TypeSlot(limit, new AtomicInteger())));
        this.slots = Map.copyOf(typeSlots);
    }

    /**
     * Создаёт политику по карте лимитов, проверяя их корректность.
     *
     * @param limits лимиты по типам задач
     * @return политика с лимитами; {@link #UNLIMITED}, если карта пустая
     */
    static PerTypeConcurrencyPolicy of(Map<? extends JobType, Integer> limits) {
        Objects.requireNonNull(limits, "limits");
        final Map<JobType, Integer> checked = new HashMap<>();
        limits.forEach((jobType, limit) -> {
            Objects.requireNonNull(jobType, "jobType");
            Objects.requireNonNull(limit, "limit");
            if (limit <= 0) {
                throw new IllegalArgumentException(
                        "Лимит типа задачи " + jobType.name() + " должен быть положительным: " + limit);
            }
            checked.put(jobType, limit);
        });
        return checked.isEmpty() ? UNLIMITED : new PerTypeConcurrencyPolicy(Map.copyOf(checked));
    }

    @Override
    public boolean tryAcquire(JobType jobType) {
        final TypeSlot slot = slots.get(jobType);
        if (slot == null) {
            // Тип без лимита: слот не нужен
            return true;
        }
        // getAndUpdate вернёт значение до изменения: инкремент был, только если слот был свободен
        return slot.running().getAndUpdate(
                current -> current < slot.limit() ? current + 1 : current) < slot.limit();
    }

    @Override
    public void release(JobType jobType) {
        final TypeSlot slot = slots.get(jobType);
        if (slot != null) {
            // В минус не уходим: освобождается только реально занятый слот
            slot.running().updateAndGet(current -> Math.max(0, current - 1));
        }
    }

    /**
     * Слот типа задачи: лимит и текущее количество занятых слотов.
     *
     * @param limit   максимальное количество одновременно выполняемых задач
     * @param running счётчик занятых слотов
     */
    private record TypeSlot(int limit, AtomicInteger running) {
    }
}
