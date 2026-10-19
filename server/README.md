# Локальный сервер AgroVoice

Каркас S-01: FastAPI, PostgreSQL, SQLAlchemy, Alembic, JWT и nginx. Пока реализованы
`GET /health`, `POST /auth/token` и `GET /me`; синхронизация осмотров и загрузка фото — S-02.
Телефон к серверу автоматически не подключается.

Из каталога `server/`:

```powershell
python setup_env.py
docker compose up -d --build
docker compose --profile test run --rm tests
```

API доступен только на этом ПК: `http://127.0.0.1:8027/health`, документация — `/docs`.
Миграции применяются перед запуском API. Секреты находятся в `.env`, в git и образ не попадают.
Тесты используют отдельную БД `agrovoice_test` и откатывают свои транзакции.
Рабочие осмотры телефона не импортируются.

Создать пользователя для локальной проверки:

```powershell
docker compose exec api python -m app.manage boris
```

Пароль вводится скрыто. Клиент получает bearer-токен, передав JSON
`{"username":"boris","password":"..."}` в `/auth/token`. Удалённое размещение,
TLS и подключение мобильной синхронизации — отдельные этапы. `docker compose down`
останавливает этот стенд и сохраняет его том с данными.
