package ru.sarteam.jobs.runner.core.io;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.nio.file.Path;

/**
 * Вход задачи сканирования директории.
 */
@Data
@AllArgsConstructor
public class ScanDirJobInput implements JobInput {

    /** Корневая директория, которую нужно рекурсивно просканировать. */
    private Path root;
}
