package ru.sarteam.jobs.runner.core.model;

import ru.sarteam.jobs.runner.core.io.JobInput;
import ru.sarteam.jobs.runner.core.io.JobOutput;

/**
 * Тип задачи — расширяемая точка: реализуется enum'ами приложения и связывает задачу
 * с классами входа и выхода.
 *
 * <p>Тип используется для поиска подходящего
 * {@link ru.sarteam.jobs.runner.core.handler.JobHandler} и
 * {@link ru.sarteam.jobs.runner.core.listener.JobResultListener}.
 */
public interface JobType {

    /**
     * Имя типа задачи (для enum — имя константы).
     *
     * @return имя типа
     */
    String name();

    /**
     * Описание типа для логов и UI.
     *
     * @return человекочитаемое описание
     */
    String getDescription();

    /**
     * Класс входа задачи этого типа.
     *
     * @return класс, реализующий {@link JobInput}
     */
    Class<? extends JobInput> getJobInputClass();

    /**
     * Класс выхода задачи этого типа.
     *
     * @return класс, реализующий {@link JobOutput}
     */
    Class<? extends JobOutput> getJobOutputClass();
}
