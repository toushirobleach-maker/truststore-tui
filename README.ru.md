# Truststore TUI

TUI-приложение для просмотра Java truststore (`JKS`/`PKCS12`) и TLS-проверок.

Основной README на английском: `README.md`.

## Возможности

- Загрузка truststore из локального файла.
- Загрузка truststore из локального `tar.gz` / `tgz` (в архиве должен быть ровно один файл).
- Загрузка truststore по URL (обычный truststore-файл или `tar.gz` / `tgz`).
- Просмотр сертификатов (`alias`, срок действия, `subject`, `issuer`, статус).
- TLS-проверка `host:port`:
  - по всему truststore;
  - по одному выбранному сертификату.
- Поиск подходящих alias для endpoint с прогрессом (`checked/total`).

## Требования

- Java 17+ (для локального запуска)
- Maven 3.9+ (для локальной сборки)
- Docker (рекомендуется)

## Локальная сборка и запуск

```bash
mvn -DskipTests package
java -jar target/truststore-tui-1.0.0-jar-with-dependencies.jar
```

## Docker

Сборка:

```bash
docker build -t truststore-tui:latest .
```

Запуск (интерактивный TUI):

```bash
docker run --rm -it -e TRUSTSTORE_PASSWORD="your_password" truststore-tui:latest
```

Запуск с монтированием `Downloads` из Windows в `read-only`:

```bash
docker run --rm -it -e TRUSTSTORE_PASSWORD="your_password" -v "C:\Users\toush\Downloads:/downloads:ro" truststore-tui:latest
```

В TUI используйте путь внутри контейнера (например, `/downloads/yourstore.jks`).

## Переменные окружения

- `TRUSTSTORE_PASSWORD` (опционально): пароль truststore. По умолчанию `changeit`.
- `TRUSTSTORE_PATH` (опционально): путь к файлу truststore.
- `TRUSTSTORE_URL` (опционально): URL на truststore-файл или `tar.gz` / `tgz`.

Поведение на старте:

- задан только `TRUSTSTORE_PATH`: загрузка из файла сразу;
- задан только `TRUSTSTORE_URL`: загрузка по URL сразу;
- заданы обе: диалог выбора источника;
- не задана ни одна: выбор между `File` и `URL`.

## Быстрое использование

1. Выберите `Source type` (`File` или `URL`).
2. Заполните `Path / URL` (или `Browse...` для режима файла).
3. Нажмите `Load truststore`.
4. Откройте `TLS check`, введите `Host` и `Port`, запустите проверку.
5. При необходимости включите `Find matching certificates`.

## Горячие клавиши

Таблица сертификатов:

- `/`: поиск по строке без учета регистра с подсветкой.
- `Ctrl+E`: скрыть/показать истекшие.
- `Ctrl+A`: сортировка по alias (`ASC/DESC`).
- `Ctrl+X`: сортировка по expiry (`ASC/DESC`).
- `Left` / `Right`: горизонтальный скролл `Subject`.
- `Enter`: детали сертификата (`TLS only this cert` в окне деталей).

Диалог выбора файла:

- `Enter` на папке или `..`: открыть/подняться выше.
- `Enter` на файле: выбрать файл.
- `Ctrl+N`: сортировка по имени (`ASC/DESC`).
- `Ctrl+D`: сортировка по дате изменения (`ASC/DESC`).

## Типовые ошибки

- `Failed to load truststore. Check password and store format (JKS/PKCS12).`
  - неверный пароль или неподдерживаемый формат.
- `tar.gz must contain exactly one file`
  - архив пуст или содержит больше одного файла.
- `Invalid URL: ...`
  - некорректный URL (схема/хост).
- `TLS validation failed: ...`
  - ошибка DNS/доступности/таймаута/SSL handshake.
