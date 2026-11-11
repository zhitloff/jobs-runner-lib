# AGENTS.md

## Что это

`jobs-runner-lib` — библиотека-ядро планировщика задач (`ru.sarteam.jobs.runner.core`), а не приложение:
`main`-класса нет, Spring в зависимостях отсутствует и библиотеке для сборки не нужен.
Предполагается встраивание в Spring Boot приложение-хост, которое даёт реализацию `JobStore`.

## Команды

- `mvn -B clean test` — сборка + тесты (mvnw/wrapper в репозитории нет, `.mvn/` пустая; используется системный Maven, локально 4.0.0-rc-7 + JDK 25).
- `mvn -Dtest=JobsRunnerTest#start test` — один тест.
- Целевая версия Java в pom — 21 (`maven.compiler.release`), хотя JDK в IDE 25. Lombok прописан в `annotationProcessorPaths` явно, потому что JDK 23+ не запускает annotation processing неявно — не удалять.

**Дженерики:** `Job` не параметризован `JobHandler`, поэтому `List<JobHandler<?, ?>>` нельзя вызывать с `JobInput` напрямую. Приведение входа сосредоточено в одном `@SuppressWarnings("unchecked")`-методе `JobsRunner.handle(...)`, по коду передаётся `JobResult<? extends JobOutput>`. Не пытайтесь «чинить» это кастами в других местах или правкой pom/JDK.

## Структура

- `core/JobsRunner` — оркестратор: берёт `JobStore.findAllWithLock`, помечает `tryMarkRunning`, запускает задачу и завершает через `completeRun`.
- `core/handler/JobHandler` — обработчик: `supports(JobType)` + `JobResult<O> handle(I)`, типизирован по входу/выходу.
- `core/io` — `JobInput`, `JobOutput`, sealed `JobResult` (`Success` / `Failure`), фабрики `JobResult.success/failure`.
- `core/listeber` — **опечатка в имени пакета** (`listener`); не переименовывать без необходимости, иначе сломаются импорты.
- `core/model` — `Job` (Lombok `@Data`), `JobType` (интерфейс с `name()`, реализуется enum'ами).
- `core/store/JobStore` — точка расширения для хранения; комментарии в интерфейсе задают семантику:
  `findAllWithLock` = `select for update`, `tryMarkRunning` = захват задачи (защита от двойного запуска),
  `completeRun(job, result, consumer)` = завершение + callback в одной транзакции; возвращает актуальное
  состояние задачи (может быть новым инстансом).

## Инварианты, которые легко нарушить

- `tryMarkRunning` вызывается **до** `handler.handle`; при `false` задача пропускается. Не менять порядок.
- При `job.isCompleteAndNotifyTogether() == true` уведомление слушателей идёт внутри `completeRun` (outbox-семантика, одна транзакция); иначе — после, по `Job`, возвращённому `completeRun` (не по исходному инстансу).
- Отсутствие обработчика для задачи логируется и пропускается, весь батч `start` не падает.
- Исключение из `handle` превращается в `JobResult.failure`, а исключение из слушателя только логируется — задача не падает.
- Каждая задача запускается в отдельном виртуальном потоке (`Thread.startVirtualThread`), без пула и лимита конкурентности.
- `JobHandler`/`JobResultListener` ищутся по первому совпадению `supports`, поэтому для одного `JobType` не должно быть двух обработчиков.

## Конвенции

- Javadoc, комментарии и логи — по-русски; логи в формате `method() - start/end/error: ...` через Lombok `@Slf4j`.
- Lombok: `@Data` для моделей, `@RequiredArgsConstructor` для конструкторов, `@Slf4j` для логов.
- Комментарии `// todo:` в модели (`Job`) — намеренные заметки автора, не удалять.

## Тесты

- JUnit 5 (surefire 3.5.2), тесты в том же пакете, что и код, — это нужно, т.к. `JobsRunner.start` объявлен `protected`.
- Тестовые реализации-фикстуры лежат в `src/test` (`ScanDirJobHandler`, `ScanDirJobInput/Output`, `JobsType`, `InmemoryJobStore`) и служат примером подключения нового типа задачи. `InmemoryJobStore` хранит задачи в `ConcurrentHashMap` и меняет тот же инстанс `Job`, поэтому сквозной сценарий через неё проходит.
- `InmemoryJobStore` эмулирует `select for update`: `findAllWithLock` атомарно лочит задачи в `lockedJobIds`, `tryMarkRunning` переводит `SCHEDULED -> RUNNING` только один раз и снимает lock (`completeRun` — страховка). `InmemoryJobStoreTest` фиксирует это поведение, `JobsRunnerTest#start_parallelCalls_doNotRunJobTwice` — что параллельный `start()` не запускает задачу дважды.
- `JobsRunnerTest#start` сканирует `@TempDir` и ждёт статус `COMPLETED` (задача выполняется в виртуальном потоке, без ожидания тест завершится раньше неё).
- SLF4J-провайдер в тестах — `slf4j-simple` (scope `test`); уровень `debug` и вывод в stdout заданы через `systemPropertyVariables` у surefire, иначе логи падают в NOP и их не видно.

## Чего в репозитории нет

CI, форматтера/lint (только локальные настройки Checkstyle в `.idea`), миграций и кодогенерации.
