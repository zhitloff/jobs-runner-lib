package ru.sarteam.jobs.runner.core.io;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import ru.sarteam.jobs.runner.core.model.JobType;

/**
 * Типы задач для тестов и примеров подключения.
 *
 * <p>Каждый тип связывает задачу с классами входа и выхода, которые ожидает обработчик.
 */
@Getter
@RequiredArgsConstructor
public enum JobsType implements JobType {

    /** Рекурсивное сканирование директории для получения списка файлов. */
    SCAN_DIRECTORY(ScanDirJobInput.class, ScanDirJobOutput.class, "Сканирование директории для получения файлов");

    /** Класс входа задачи этого типа. */
    private final Class<? extends JobInput> jobInputClass;
    /** Класс выхода задачи этого типа. */
    private final Class<? extends JobOutput> jobOutputClass;
    /** Человекочитаемое описание типа. */
    private final String description;
}
