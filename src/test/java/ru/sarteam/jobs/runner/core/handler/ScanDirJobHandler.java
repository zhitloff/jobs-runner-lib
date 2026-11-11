package ru.sarteam.jobs.runner.core.handler;

import lombok.extern.slf4j.Slf4j;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.io.JobsType;
import ru.sarteam.jobs.runner.core.io.ScanDirJobInput;
import ru.sarteam.jobs.runner.core.io.ScanDirJobOutput;
import ru.sarteam.jobs.runner.core.model.JobType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Пример обработчика задачи сканирования директории: рекурсивно собирает пути файлов.
 *
 * <p>Служит образцом подключения нового типа задачи в тестах и примерах.
 */
@Slf4j
public class ScanDirJobHandler implements JobHandler<ScanDirJobInput, ScanDirJobOutput> {

    /**
     * Обработчик отвечает за задачи типа {@link JobsType#SCAN_DIRECTORY}.
     *
     * @param jobType имя типа задачи
     * @return {@code true}, если тип задачи — {@link JobsType#SCAN_DIRECTORY}
     */
    @Override
    public boolean supports(JobType jobType) {
        return JobsType.SCAN_DIRECTORY.equals(jobType);
    }

    /**
     * Обходит директорию {@code jobInput.getRoot()} и собирает абсолютные пути файлов.
     *
     * <p>Ошибка ввода-вывода (например, директории нет) не выбрасывается наружу,
     * а возвращается как {@link JobResult#failure}.
     *
     * @param jobInput вход задачи с корневой директорией
     * @return выход со списком путей файлов либо ошибка чтения директории
     */
    @Override
    public JobResult<ScanDirJobOutput> handle(ScanDirJobInput jobInput) {
        log.debug("handle() - start: jobInput = {}", jobInput);
        try (Stream<Path> stream = Files.walk(jobInput.getRoot())) {
            List<String> pathFiles = stream
                    .filter(Files::isRegularFile)
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .toList();
            ScanDirJobOutput jobOutput = new ScanDirJobOutput(pathFiles);
            log.debug("handle() - end: jobOutput = {}", jobOutput);
            return JobResult.success(jobOutput);
        } catch (IOException e) {
            log.error("handle() - error scan by jobInput = {}", jobInput, e);
            return JobResult.failure(e);
        }
    }
}
