# Развёртывание Simplified Link Tracker в Kubernetes

Гайд рассчитан на локальный Kubernetes в Docker Desktop. Все команды выполняются
из корня проекта. Потребуются Docker Desktop, `kubectl`, Git и Python 3.
Java и Maven на компьютере для Docker-сборки не нужны.

Исходники: [Sushcheva/simplified-link-tracker](https://github.com/Sushcheva/simplified-link-tracker).
Kubernetes запускает контейнеры из образа приложения; репозиторий GitHub хранит код
и сам по себе не служит реестром контейнерных образов.

## Что будет работать

| Объект | Назначение |
|---|---|
| Namespace `link-tracker` | Группа ресурсов этого проекта |
| StatefulSet `postgresql`, 1 Pod | PostgreSQL 16.4 |
| PVC `data-postgresql-0`, 2 GiB | Диск базы данных, переживающий замену Pod |
| Service `postgresql` | Постоянное DNS-имя базы внутри namespace |
| ConfigMap с идентификатором релиза | Адреса API, интервалы, таймауты и другие несекретные настройки |
| Secret `link-tracker-secrets` | Пароль БД и необязательный токен GitHub |
| Job `link-tracker-migrate-<релиз>` | Однократное применение миграций |
| Deployment `link-tracker-web`, 2 Pod | REST API и выдача HTML/CSS/JavaScript |
| Deployment `link-tracker-worker`, 1 Pod | Фоновая проверка ссылок |
| Service `link-tracker-web` | Внутренний HTTP-адрес готовых веб-экземпляров |

Pod — запускаемая Kubernetes единица, здесь с одним контейнером.
Deployment поддерживает нужное число экземпляров приложения.
StatefulSet закрепляет идентичность экземпляра БД и связанного диска.
Job выполняет задачу до завершения. Service даёт процессам стабильный сетевой адрес.
[Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/),
[StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/),
[Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/).

```mermaid
flowchart LR
    Browser[Браузер] -->|localhost:8080| Forward[kubectl port-forward]
    Forward --> Web[Один выбранный web Pod]
    Service[Service link-tracker-web] --> Web
    Service --> Web2[Второй web Pod]
    Web --> DB[(PostgreSQL + PVC)]
    Web2 --> DB
    Worker[worker] --> DB
    Worker --> API[GitHub / Stack Exchange]
    Job[Разовый migrate Job] --> DB
```

В приложении нет авторизации. Этот сценарий использует внутренний ClusterIP и
локальный port-forward; он подходит для учебного доступа с собственного компьютера.
Публикация в интернет потребует отдельного решения по доступу и TLS.

## 1. Подготовить Docker Desktop

1. Запустить Docker Desktop.
2. Включить Kubernetes. В новых версиях это раздел **Kubernetes → Create cluster**;
   в старых — **Settings → Kubernetes → Enable Kubernetes**.
3. Если доступен выбор, для этого сценария выбрать **kubeadm**, один узел.
4. Дождаться готовности кластера.

Основной сценарий использует локальный образ Docker Desktop и
`imagePullPolicy: IfNotPresent`. Для режима kind или отдельного удалённого кластера
может потребоваться загрузка образа в узлы либо публикация в реестре.
Параметры кластеров описаны в [документации Docker Desktop](https://docs.docker.com/desktop/use-desktop/kubernetes/).

Для трёх Java-процессов, БД и самого кластера разумно начать с 4 CPU и 6 GiB памяти
в настройках Docker Desktop. Это начальная оценка; фактическое потребление нужно
измерить. В манифестах заданы requests и limits для каждого контейнера.

Проверить подключение:

```bash
docker info
kubectl config get-contexts
kubectl config use-context docker-desktop
kubectl get nodes
kubectl get storageclass
kubectl version
```

Ожидается узел со статусом `Ready` и StorageClass с пометкой `(default)`.
Все дальнейшие команды относятся к контексту `docker-desktop`.
Если контекста нет, сначала нужно закончить включение Kubernetes.
Версию `kubectl` следует держать в пределах одного minor-релиза от API-сервера.
[Правила совместимости версий](https://kubernetes.io/releases/version-skew-policy/#kubectl).

## 2. Собрать образ и подготовить релиз

Сначала убедиться, что изменения зафиксированы:

```bash
git status --short
```

Пустой вывод означает чистое рабочее дерево. Для релиза сохранить текущий коммит,
выбрать уникальное имя и собрать образ:

```bash
export RELEASE_ID="r$(date -u +%Y%m%d%H%M%S)-$(git rev-parse --short=8 HEAD)"
export APP_IMAGE="simplified-link-tracker:$RELEASE_ID"
docker build --label "org.opencontainers.image.revision=$(git rev-parse HEAD)" \
  -t "$APP_IMAGE" .
python3 scripts/render_k8s.py --release "$RELEASE_ID" --image "$APP_IMAGE"
export RELEASE_DIR=".local/k8s/$RELEASE_ID"
docker image inspect "$APP_IMAGE" --format '{{.Id}}'
```

Скрипт создаёт четыре готовых манифеста и `release.json` в `$RELEASE_DIR`.
В Job, web и worker будет один образ; ConfigMap получит имя с идентификатором релиза
и `immutable: true`. Исходные шаблоны в `k8s/` остаются общими для всех релизов.

Не следует выполнять `kubectl apply -f k8s/`: часть файлов — шаблоны с подстановками,
а БД, миграции и приложение требуют последовательного запуска.
Не следует пересобирать другой код под уже использованным тегом релиза.
Каталог `.local/` исключён из Git, Docker-контекста и исходного архива.

Записать проверяемые сведения о сборке рядом с манифестами:

```bash
git rev-parse HEAD > "$RELEASE_DIR/commit.txt"
docker image inspect "$APP_IMAGE" --format '{{.Id}}' > "$RELEASE_DIR/image-id.txt"
```

При повторном открытии терминала нужно снова задать `RELEASE_ID`, `APP_IMAGE`
и `RELEASE_DIR` для уже подготовленного релиза. Повторный запуск генератора с тем же
идентификатором отклоняется, чтобы случайно не перезаписать комплект манифестов.

## 3. Создать namespace и Secret

```bash
kubectl apply -f k8s/namespace.yaml
cp k8s/secret.env.example .env.k8s
chmod 600 .env.k8s
```

Открыть `.env.k8s` и заменить `DATABASE_PASSWORD` собственным локальным паролем.
`GITHUB_TOKEN` можно оставить пустым. Файл исключён из Git и архива.

Создать Secret один раз:

```bash
kubectl -n link-tracker create secret generic link-tracker-secrets \
  --from-env-file=.env.k8s
```

Если Secret уже существует, команда завершится ошибкой `AlreadyExists`.
При последующих релизах используется существующий Secret.

Значения из Secret попадают в переменные окружения процессов.
Secret отделяет конфиденциальные настройки от исходников, но не означает
автоматического шифрования данных в хранилище кластера.
[Модель Kubernetes Secrets](https://kubernetes.io/docs/concepts/configuration/secret/).

Пароль PostgreSQL применяется при первоначальном создании её хранилища.
Редактирование Secret само по себе не меняет пароль существующего пользователя БД.
Поэтому при повторном развёртывании с тем же PVC нужно сохранять согласованный пароль.
Ротация пароля требует отдельной операции в PostgreSQL и обновления потребителей.

## 4. Запустить PostgreSQL

```bash
kubectl apply -f k8s/postgresql.yaml
kubectl -n link-tracker rollout status statefulset/postgresql --timeout=180s
kubectl -n link-tracker get pods,pvc
```

Ожидается Pod `postgresql-0` со статусом `Running`, готовностью `1/1`
и PVC `data-postgresql-0` со статусом `Bound`.

Если PVC остаётся `Pending`, посмотреть:

```bash
kubectl -n link-tracker describe pvc data-postgresql-0
kubectl get storageclass
```

Манифест использует StorageClass по умолчанию. Для другого кластера может понадобиться
явно указать `storageClassName` в `volumeClaimTemplates`.
Одна PostgreSQL с одним PVC подходит для учебного стенда; репликация и резервное
копирование этим манифестом не настраиваются.

## 5. Выполнить миграции

```bash
kubectl apply -f "$RELEASE_DIR/configmap.yaml"
kubectl apply -f "$RELEASE_DIR/migrate.yaml"
kubectl -n link-tracker wait --for=condition=complete \
  "job/link-tracker-migrate-$RELEASE_ID" --timeout=180s
kubectl -n link-tracker logs "job/link-tracker-migrate-$RELEASE_ID"
```

Продолжать нужно только после успешного завершения Job.
Он запускает тот же образ, включает Liquibase, отключает HTTP и планировщик,
после инициализации завершает процесс.

Kubernetes не использует `depends_on` из Compose. Порядок обеспечивают команды гайда:
готовая БД → завершённая миграция → запуск приложения.
При ошибке ожидания сначала проверить логи Job и состояние Pod, а не запускать web.

## 6. Запустить web и worker

```bash
kubectl apply -f "$RELEASE_DIR/web.yaml"
kubectl apply -f "$RELEASE_DIR/worker.yaml"
kubectl -n link-tracker rollout status deployment/link-tracker-web --timeout=240s
kubectl -n link-tracker rollout status deployment/link-tracker-worker --timeout=240s
kubectl -n link-tracker get deployments,pods,services,jobs
```

Ожидаются два готовых web Pod, один работающий worker и PostgreSQL.
Веб-процессы имеют `SCHEDULER_ENABLED=false`; worker работает без HTTP-сервера.

Для web заданы три проверки:

- **startupProbe** даёт время на запуск JVM и Spring;
- **livenessProbe** проверяет, живо ли приложение, без зависимости от состояния БД;
- **readinessProbe** учитывает БД и определяет готовность получать трафик Service.

Разделение предотвращает перезапуск всех web Pod только из-за краткого сбоя БД.
[Назначение Kubernetes probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/).

У worker нет HTTP-probes. Статус `Running` подтверждает работу процесса;
выполнение мониторинга нужно проверять по логам и изменениям записей.

## 7. Открыть интерфейс и проверить работу

Запустить в отдельном терминале:

```bash
kubectl -n link-tracker port-forward service/link-tracker-web 8080:80
```

Открыть [http://localhost:8080](http://localhost:8080). Команда остаётся работающей
до Ctrl+C. Если локальный порт занят, использовать `8081:80` и адрес с портом 8081.

Проверки:

```bash
curl --fail http://localhost:8080/actuator/health/readiness
python3 scripts/check_api.py http://localhost:8080
kubectl -n link-tracker logs deployment/link-tracker-worker --tail=50
```

Скрипт создаёт и удаляет свои тестовые ссылки. Без локального HTTP-fixture он проверяет
доступные общие сценарии API; полный набор сценариев мониторинга выполняется через
`verify-local.sh` с тестовыми ответами внешних сервисов.

Через браузер добавить реальный репозиторий GitHub, изменить название и теги,
приостановить отслеживание, выполнить ручную проверку и удалить запись.
Для фонового мониторинга оставить ссылку включённой и дождаться прохода планировщика.
Первое успешное обращение только запоминает исходную отметку ресурса.

`port-forward service/...` выбирает один Pod и направляет соединение к нему.
Он удобен для доступа с компьютера, но не демонстрирует распределение запросов
через ClusterIP. Готовые адреса Service можно проверить так:

```bash
kubectl -n link-tracker get endpointslices \
  -l kubernetes.io/service-name=link-tracker-web
```

## 8. Проверить масштабирование и перезапуск

Увеличить число веб-процессов:

```bash
kubectl -n link-tracker scale deployment/link-tracker-web --replicas=3
kubectl -n link-tracker rollout status deployment/link-tracker-web --timeout=240s
kubectl -n link-tracker get pods -l app=link-tracker-web
```

Для worker можно отдельно задать две реплики:

```bash
kubectl -n link-tracker scale deployment/link-tracker-worker --replicas=2
kubectl -n link-tracker rollout status deployment/link-tracker-worker --timeout=240s
kubectl -n link-tracker logs -l app=link-tracker-worker --prefix --tail=50
```

Общая БД и `FOR UPDATE SKIP LOCKED` координируют выбор ссылок.
Рост числа процессов не снимает лимиты внешних API и ограничения PostgreSQL.
Для постоянного изменения количества реплик нужно изменить шаблон и выпустить
следующий релиз: очередной apply исходной конфигурации возвращает записанное число.

Проверить сохранность данных: добавить ссылку в интерфейсе, затем выполнить:

```bash
kubectl -n link-tracker rollout restart deployment/link-tracker-web
kubectl -n link-tracker rollout status deployment/link-tracker-web --timeout=240s
```

При замене Pod команда port-forward может завершиться; запустить её заново.
Ссылка должна остаться в коллекции. Эта проверка подтверждает сохранность записи
при штатном перезапуске, но не заменяет проверку остановки под нагрузкой.

В Kubernetes процессу даётся 60 секунд на завершение. В Spring настроены graceful
shutdown и остановка планировщика. Для полноценной проверки фактора IX нужно отдельно
измерить холодный старт и завершение текущей проверки по SIGTERM, затем проверить
восстановление незавершённой операции после аварийной остановки.

## 9. Обновить версию и при необходимости вернуться к предыдущей

Для каждого изменения кода или конфигурации:

1. Зафиксировать исходники и шаблоны в Git.
2. Создать новый `RELEASE_ID`, собрать новый образ и выполнить генератор из шага 2.
3. Применить новый ConfigMap и выполнить Job миграции из шага 5.
4. Только после успешной миграции применить web и worker из шага 6.
5. Проверить готовность, API и логи. Сохранить образ и каталог релиза.

RollingUpdate веб-сервера использует `maxUnavailable: 0` и `maxSurge: 1`.
Новая версия должна стать готовой перед удалением старого экземпляра.
На время обновления старый и новый код могут работать одновременно, поэтому
изменения схемы БД должны быть совместимы с обеими версиями.
[Механизм обновления Deployment](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/#updating-a-deployment).

Для просмотра истории:

```bash
kubectl -n link-tracker rollout history deployment/link-tracker-web
kubectl -n link-tracker rollout history deployment/link-tracker-worker
```

Если предыдущие web и worker составляли один согласованный релиз, а схема БД
совместима с ним, можно вернуть обе предыдущие ревизии:

```bash
kubectl -n link-tracker rollout undo deployment/link-tracker-web
kubectl -n link-tracker rollout undo deployment/link-tracker-worker
kubectl -n link-tracker rollout status deployment/link-tracker-web --timeout=240s
kubectl -n link-tracker rollout status deployment/link-tracker-worker --timeout=240s
```

Undo возвращает предыдущий шаблон Pod, включая образ и ссылку на ConfigMap.
Он не откатывает PostgreSQL, миграции и содержимое Secret. Старые образы
и ConfigMap должны оставаться доступными. Если предыдущие ревизии web и worker
не совпадают по релизу, применить их манифесты из одного сохранённого каталога
`.local/k8s/<релиз>`.

Текущая схема ещё не обеспечивает полный учёт релизов: Secret имеет постоянное имя,
локальный образ можно перезаписать, `.local/` хранится только на компьютере.
Для более строгого выполнения фактора V нужны образы по digest в реестре,
контролируемое хранение комплектов релиза и версия секретной конфигурации.

## 10. Диагностика и остановка

```bash
kubectl -n link-tracker get pods
kubectl -n link-tracker get events --sort-by=.metadata.creationTimestamp
kubectl -n link-tracker logs -l app=link-tracker-web --prefix --tail=100
kubectl -n link-tracker logs -l app=link-tracker-worker --prefix --tail=100
kubectl -n link-tracker logs postgresql-0 --tail=100
```

| Симптом | Что проверить |
|---|---|
| `ImagePullBackOff` / `ErrImagePull` | Совпадает ли тег; доступен ли локальный образ движку Kubernetes; при другом кластере нужен реестр или загрузка образа |
| `CreateContainerConfigError` | Созданы ли Secret и ConfigMap нужного релиза |
| PVC `Pending` | Есть ли подходящий StorageClass и доступное хранилище |
| Job `Failed` | Логи миграции, доступ к БД и правильность пароля |
| web не готов | `/actuator/health/readiness`, логи web и состояние PostgreSQL |
| worker `Running`, проверок нет | Логи, наличие включённых ссылок, срок следующей проверки и лимиты API |
| `OOMKilled` | Память контейнера и лимиты Docker Desktop; скорректировать requests/limits по наблюдениям |

Остановить приложение, сохранив БД и её диск:

```bash
kubectl -n link-tracker scale deployment/link-tracker-web --replicas=0
kubectl -n link-tracker scale deployment/link-tracker-worker --replicas=0
```

При необходимости также остановить PostgreSQL:

```bash
kubectl -n link-tracker scale statefulset/postgresql --replicas=0
```

Для возобновления сначала вернуть PostgreSQL к одной реплике и дождаться готовности,
затем вернуть web к двум, worker к одной. В этой конфигурации уменьшение числа
реплик сохраняет PVC. Удаление namespace/PVC или сброс кластера может удалить данные;
для обычной остановки эти действия не нужны.

## Что это даёт для 12 факторов

| Фактор | Что предусмотрено | Что ещё подтвердить |
|---|---|---|
| I. Кодовая база | Один Git-репозиторий для модулей и развёртывания | Связывать каждый релиз с коммитом |
| III. Конфигурация | ConfigMap и Secret передаются через окружение | Порядок изменения и хранения секретов |
| V. Сборка, релиз, выполнение | Отдельная сборка, идентификатор релиза, сохранённые манифесты, неизменяемый ConfigMap | Неизменяемые образы и полная история конфигурации, проверенный откат |
| VI. Процессы | Данные находятся в PostgreSQL на PVC | Проверка сохранности после перезапуска |
| VIII. Параллелизм | Отдельные web и worker Deployment, Service и несколько web Pod | Реальное масштабирование и работа нескольких worker |
| IX. Утилизируемость | Probes, graceful shutdown, лимит завершения и транзакции | Замеры старта, SIGTERM под нагрузкой, восстановление после сбоя |
| X. Паритет окружений | PostgreSQL 16.4, общий образ и миграции | Интеграционные тесты и проверка того же образа в окружениях |
| XII. Администрирование | Отдельный Job миграции из образа релиза | Успешное выполнение в кластере |

Состояние на 1 октября 2026 года: манифесты и команды подготовлены, но этот Kubernetes-
сценарий ещё не запускался. При проверке на компьютере не было настроенного
Kubernetes-контекста. Выполнение шагов и фиксация результатов дадут доказательства
для отчёта; наличие YAML-файлов само по себе их не заменяет.
