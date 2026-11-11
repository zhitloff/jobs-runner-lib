package ru.sarteam.jobs.runner.core.listener;

import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.model.JobType;

/**
 * Слушатель результатов задач — точка расширения для реакции на завершение задачи
 * (уведомления, аудит, outbox и т.п.).
 *
 * <p>Слушатель вызывается {@link ru.sarteam.jobs.runner.core.JobsRunner} после завершения
 * задачи. Исключение из слушателя только логируется и не роняет задачу; для одного типа
 * задачи используется первый подходящий слушатель.
 */
public interface JobResultListener {

    /**
     * Нужно ли вызывать этого слушателя для задачи указанного типа.
     *
     * @param jobType имя типа задачи
     * @return {@code true}, если слушатель обрабатывает задачи этого типа
     */
    boolean supports(JobType jobType);

    /**
     * Задача завершилась успешно.
     *
     * <p>Метод по умолчанию ничего не делает: слушателю не обязательно реагировать на успех.
     *
     * @param job       актуальное состояние задачи
     * @param jobResult значение из {@link ru.sarteam.jobs.runner.core.io.JobResult.Success}
     */
    default void onSuccess(Job job, JobResult<? extends JobOutput> jobResult) {
    }

    /**
     * Задача завершилась с ошибкой.
     *
     * <p>Метод по умолчанию ничего не делает: слушателю не обязательно реагировать на ошибку.
     *
     * @param job       актуальное состояние задачи
     * @param jobResult значение из {@link ru.sarteam.jobs.runner.core.io.JobResult.Failure}
     */
    default void onFailure(Job job, JobResult<? extends JobOutput> jobResult) {
    }
}
