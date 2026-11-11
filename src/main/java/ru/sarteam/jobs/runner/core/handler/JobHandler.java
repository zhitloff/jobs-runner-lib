package ru.sarteam.jobs.runner.core.handler;


import ru.sarteam.jobs.runner.core.io.JobInput;
import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.model.JobType;

/**
 * Базовый интерфейс для всех обработчиков задач.
 *
 * <p>Обработчик типизирован входом и выходом задачи: {@code I} — вход, {@code O} — выход.
 * Для одного {@link JobType} должен существовать ровно один обработчик, так как
 * {@link ru.sarteam.jobs.runner.core.JobsRunner} берёт первый подходящий.
 *
 * @param <I> тип входа задачи
 * @param <O> тип выхода задачи
 */
public interface JobHandler<I extends JobInput, O extends JobOutput> {

    /**
     * Нужно ли вызывать этот обработчик для задачи указанного типа.
     *
     * @param jobType имя типа задачи
     * @return {@code true}, если обработчик умеет выполнять задачи этого типа
     */
    boolean supports(JobType jobType);

    /**
     * Основной метод обработки задачи.
     *
     * <p>Исключение из метода превращается в {@link JobResult#failure} силами
     * {@link ru.sarteam.jobs.runner.core.JobsRunner}, поэтому обработчику не обязательно
     * перехватывать все ошибки самому.
     *
     * @param jobInput вход задачи
     * @return результат выполнения: {@link JobResult#success} или {@link JobResult#failure}
     */
    JobResult<O> handle(I jobInput);
}
