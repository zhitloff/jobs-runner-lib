# jobs-runner-lib

Библиотека-ядро планировщика задач: выбирает готовые задачи из хранилища, захватывает их
и выполняет каждую в отдельном виртуальном потоке. Не приложение: `main`-класса нет,
библиотека встраивается в приложение-хост, которое даёт реализацию `JobStore`
(БД, очередь, in-memory и т.п.).

- **Координаты:** `ru.sarteam:jobs-runner-lib:1.0.0`
- **Пакет:** `ru.sarteam.jobs.runner.core`
- **Java:** 21 (`maven.compiler.release`)
- **Зависимости:** Lombok (`provided`), SLF4J API; Spring не требуется

## Возможности

- Единый цикл запуска: выборка без блокировки → захват → выполнение → завершение.
- Защита от двойного запуска задачи при параллельных вызовах `start()` (`tryMarkRunning`).
- Выполнение задач в виртуальных потоках без пула и лимита конкурентности.
- Типизированные обработчики: тип входа и выхода задаётся через `JobHandler<I, O>`.
- Расширяемые типы задач: `JobType` реализуется enum'ами приложения.
- Слушатели результата для уведомлений, аудита и outbox-сценариев.
- Ошибка обработчика превращается в `JobResult.Failure`; ошибка слушателя только логируется.

## Сборка и тесты

```bash
mvn -B clean test
mvn -Dtest=JobsRunnerTest#start test   # один тест
```

Maven Wrapper в репозитории нет, `.mvn/` пустая: используется системный Maven.
Lombok прописан в `annotationProcessorPaths` явно, потому что JDK 23+ не запускает
annotation processing неявно.

## Как это работает

`JobsRunner.start(now, limit)` выполняет для каждой выбранной задачи:

1. `JobStore.findAllByLimit(now, limit)` — выборка готовых задач без блокировки. Одну и ту же
   задачу могут получить параллельные вызовы: это нормально, выполнит её только тот,
   кто успешно захватит её на следующем шаге.
2. Поиск обработчика по `JobType` (первое совпадение `JobHandler.supports`).
   Если обработчика нет, задача логируется и пропускается, пачка не падает.
3. `JobStore.tryMarkRunning(jobId, now)` — атомарный захват задачи. Если вернулось `false`,
   задача пропускается. Метод вызывается **до** выполнения обработчика.
4. `JobHandler.handle(jobInput)` — выполнение задачи.
5. `JobStore.completeRun(jobId, result, callback)` — завершение задачи и callback
   в одной транзакции; возвращает актуальное состояние задачи.

Каждая задача выполняется в отдельном виртуальном потоке (`Thread.startVirtualThread`),
поэтому сам `start()` не ждёт завершения задач.

### Жизненный цикл задачи

```
SCHEDULED ──tryMarkRunning──▶ RUNNING ──completeRun──▶ COMPLETED
                                                     └▶ ERROR
```

- `SCHEDULED` — задача поставлена в очередь и ждёт запуска.
- `RUNNING` — задача захвачена на выполнение.
- `COMPLETED` — успешный результат, выход сохраняется в `Job.jobOutput`.
- `ERROR` — ошибка, текст сохраняется в `Job.error`.

Статус, выход/ошибку и время завершения проставляет
`JobStore.enrichJobByResult(job, result)` — метод по умолчанию в интерфейсе `JobStore`;
реализация может использовать его или заполнять поля сама.

### Outbox-семантика

Если у задачи `completeAndNotifyTogether == true`, слушатели вызываются **внутри**
`completeRun`, в той же транзакции, что и завершение задачи. Иначе задача сначала
завершается, а слушатели вызываются после, уже по состоянию, возвращённому `completeRun`
(не по исходному инстансу `Job`).

## Ключевые компоненты

| Компонент | Назначение |
|---|---|
| `core.JobsRunner` | Оркестратор: выборка, захват, запуск в виртуальном потоке, завершение и нотификация. |
| `core.handler.JobHandler<I, O>` | Обработчик задачи: `supports(JobType)` + `JobResult<O> handle(I)`. |
| `core.listener.JobResultListener` | Слушатель результата: `supports(JobType)`, `onSuccess`, `onFailure`. |
| `core.io.JobInput` | Маркерный интерфейс входа задачи. |
| `core.io.JobOutput` | Маркерный интерфейс выхода задачи. |
| `core.io.JobResult<O>` | Sealed-результат: `Success` / `Failure`; фабрики `success()` / `failure()`. |
| `core.model.Job` | Мутабельная модель задачи (Lombok `@Data`). |
| `core.model.JobType` | Тип задачи: `name()`, `getDescription()`, классы входа и выхода. |
| `core.model.JobStatus` | Статусы `SCHEDULED`, `RUNNING`, `COMPLETED`, `ERROR`. |
| `core.store.JobStore` | Точка расширения хранения; задаёт транзакционную семантику. |

## Подключение в приложение-хост

1. **Реализуйте `JobStore`.** Обязательны `add`, `addAll`, `findAllByLimit`,
   `tryMarkRunning`, `completeRun`; `enrichJobByResult` можно использовать как есть.
   В `src/test` есть `InmemoryJobStore` — рабочий пример in-memory хранилища,
   не предназначенный для production.
2. **Опишите тип задачи** enum'ом, реализующим `JobType`.
3. **Опишите вход и выход** задачи классами, реализующими `JobInput` и `JobOutput`.
4. **Реализуйте `JobHandler`** для этого типа.
5. **При необходимости реализуйте `JobResultListener`** для реакции на завершение.
6. **Создайте `JobsRunner`** и вызывайте `start(...)` по расписанию.

`JobsRunner.start` объявлен `protected`, поэтому вызывать его нужно из наследника
(или из класса в пакете `ru.sarteam.jobs.runner.core`).

Пример по образцу фикстур из `src/test`:

```java
// 1. Тип задачи
@Getter
@RequiredArgsConstructor
public enum ScanDirJobType implements JobType {
    SCAN_DIRECTORY(ScanDirJobInput.class, ScanDirJobOutput.class, "Сканирование директории");

    private final Class<? extends JobInput> jobInputClass;
    private final Class<? extends JobOutput> jobOutputClass;
    private final String description;
}

// 2. Вход и выход
@Data
@AllArgsConstructor
public class ScanDirJobInput implements JobInput {
    private Path root;
}

@Data
@AllArgsConstructor
public class ScanDirJobOutput implements JobOutput {
    private List<String> pathFiles;
}

// 3. Обработчик
public class ScanDirJobHandler implements JobHandler<ScanDirJobInput, ScanDirJobOutput> {
    @Override
    public boolean supports(JobType jobType) {
        return ScanDirJobType.SCAN_DIRECTORY.equals(jobType);
    }

    @Override
    public JobResult<ScanDirJobOutput> handle(ScanDirJobInput jobInput) {
        try (Stream<Path> stream = Files.walk(jobInput.getRoot())) {
            List<String> files = stream.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().toString())
                    .toList();
            return JobResult.success(new ScanDirJobOutput(files));
        } catch (IOException e) {
            return JobResult.failure(e);
        }
    }
}

// 4. Слушатель (все методы есть по умолчанию)
public class LoggingJobResultListener implements JobResultListener {
    @Override
    public boolean supports(JobType jobType) {
        return ScanDirJobType.SCAN_DIRECTORY.equals(jobType);
    }
}

// 5. Запуск по расписанию
public class JobScheduler extends JobsRunner {

    public JobScheduler(JobStore jobStore) {
        super(
                List.of(new ScanDirJobHandler()),
                List.of(new LoggingJobResultListener()),
                jobStore);
    }

    public void poll() {
        start(Instant.now(), 100);
    }
}
```

## Важные инварианты

- `tryMarkRunning` вызывается **до** `handle`; при `false` задача пропускается.
- `findAllByLimit` не блокирует задачи: параллельные `start()` могут выбрать одну и ту же задачу,
  но выполнит её только успешный `tryMarkRunning` — это единственная защита от двойного запуска.
- `completeRun` возвращает актуальное состояние: реализация вправе вернуть новый инстанс,
  поэтому дальше нужно использовать возвращённое значение.
- Для одного `JobType` должен быть ровно один обработчик: выбирается первый совпавший.
  Слушатели тоже выбираются по первому совпадению `supports`.
- Исключение из `JobHandler.handle` превращается в `JobResult.failure`; исключение
  из слушателя только логируется и не роняет задачу.
- Отсутствие обработчика не останавливает обработку остальных задач пачки.
- `Job` не параметризован `JobHandler`, поэтому вход приводится к типу обработчика
  в одном месте — `JobsRunner.handle(...)` с `@SuppressWarnings("unchecked")`.
- Задачи выполняются в виртуальных потоках без ограничения конкурентности: лимит
  `start(now, limit)` ограничивает только размер пачки.

## Структура проекта

```
src/main/java/ru/sarteam/jobs/runner/core/
├── JobsRunner.java              # оркестратор
├── handler/JobHandler.java      # интерфейс обработчика
├── io/                          # JobInput, JobOutput, JobResult
├── listener/JobResultListener.java
├── model/                       # Job, JobStatus, JobType
└── store/JobStore.java          # точка расширения хранения

src/test/java/ru/sarteam/jobs/runner/core/
├── JobsRunnerTest.java          # сквозной запуск и защита от двойного запуска
├── handler/ScanDirJobHandler.java
├── io/                          # JobsType, ScanDirJobInput/Output
├── listener/ScanDirJobResultListener.java
└── store/                       # InmemoryJobStore, InmemoryJobStoreTest
```

Фикстуры в `src/test` (`ScanDirJobHandler`, `ScanDirJobInput/Output`, `JobsType`,
`InmemoryJobStore`) служат образцом подключения нового типа задачи.

## Тесты

- JUnit 5 + surefire 3.5.2; тесты лежат в том же пакете, что и код, потому что
  `JobsRunner.start` объявлен `protected`.
- `JobsRunnerTest#start` сканирует `@TempDir` и ждёт статус `COMPLETED`
  (задача выполняется в виртуальном потоке асинхронно).
- `JobsRunnerTest#start_parallelCalls_doNotRunJobTwice` проверяет, что параллельные
  `start()` не запускают одну задачу дважды.
- `InmemoryJobStoreTest` фиксирует неблокирующую выборку и однократный захват.
- Провайдер SLF4J в тестах — `slf4j-simple` (`test`); уровень `debug` и вывод в stdout
  заданы через `systemPropertyVariables` у surefire.

## Ограничения

- `InmemoryJobStore` — тестовая реализация, не транзакционная и не для production.
- Нет пула и лимита одновременных задач: на каждую задачу создаётся виртуальный поток.
- Нет встроенного планировщика: `start(...)` нужно вызывать извне (например, по cron
  в приложении-хосте).
- Нет CI, форматтера/lint и миграций.
