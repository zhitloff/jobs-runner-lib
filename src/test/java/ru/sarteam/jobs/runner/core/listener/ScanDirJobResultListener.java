package ru.sarteam.jobs.runner.core.listener;

import lombok.extern.slf4j.Slf4j;
import ru.sarteam.jobs.runner.core.io.JobOutput;
import ru.sarteam.jobs.runner.core.io.JobResult;
import ru.sarteam.jobs.runner.core.io.JobsType;
import ru.sarteam.jobs.runner.core.model.Job;
import ru.sarteam.jobs.runner.core.model.JobType;

/**
 * Пример слушателя задачи сканирования директории: логирует успех или ошибку выполнения.
 *
 * <p>Служит образцом подключения слушателя результата в тестах и примерах.
 */
@Slf4j
public class ScanDirJobResultListener implements JobResultListener {

    /**
     * Слушатель отвечает за задачи типа {@link JobsType#SCAN_DIRECTORY}.
     *
     * @param jobType имя типа задачи
     * @return {@code true}, если тип задачи — {@link JobsType#SCAN_DIRECTORY}
     */
    @Override
    public boolean supports(JobType jobType) {
        return JobsType.SCAN_DIRECTORY.equals(jobType);
    }

    /**
     * Логирует успешное завершение задачи.
     *
     * @param job       актуальное состояние задачи
     * @param jobResult успешный результат выполнения
     */
    @Override
    public void onSuccess(Job job, JobResult<? extends JobOutput> jobResult) {
        log.info("onSuccess() - job = {}, jobResult = {}", job, jobResult);
    }

    /**
     * Логирует завершение задачи с ошибкой.
     *
     * @param job       актуальное состояние задачи
     * @param jobResult результат выполнения с ошибкой
     */
    @Override
    public void onFailure(Job job, JobResult<? extends JobOutput> jobResult) {
        log.error("onFailure() - job = {}, jobResult = {}", job, jobResult);
    }
}
