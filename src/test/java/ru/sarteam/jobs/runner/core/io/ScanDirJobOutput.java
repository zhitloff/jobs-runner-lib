package ru.sarteam.jobs.runner.core.io;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Выход задачи сканирования директории.
 */
@Data
@AllArgsConstructor
public class ScanDirJobOutput implements JobOutput {

    /** Абсолютные пути найденных файлов. */
    private List<String> pathFiles;
}
