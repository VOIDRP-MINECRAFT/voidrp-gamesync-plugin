# VoidRP Game Sync Plugin

Paper 1.21.1 плагин для синхронизации игровых данных с бэкендом VoidRP: статистика государств, состав, рефералы.

## Требования

- Paper / Purpur 1.21.1
- Vault (для считывания балансов игроков)
- Java 21

## Сборка

```bash
cd voidrp_gamesync_plugin
./gradlew shadowJar
# → build/libs/voidrp-game-sync-paper-1.0.0-all.jar
```

## Установка

1. Положить jar в `plugins/`
2. Перезапустить сервер
3. Заполнить конфиги в `plugins/VoidRpGameSync/`

## Конфигурация

**`config.yml`** — URL бэкенда, секрет, интервал синхронизации:
```yaml
backend:
  url: https://api.void-rp.ru/api/v1
  secret: your-game-auth-secret
sync:
  interval_ticks: 6000   # каждые 5 минут
```

**`nations.yml`** — маппинг slug → состав государства (пока статичный):
```yaml
nations:
  darkwood:
    members: [YannGotti, Player2]
  ironhold:
    members: [Player3]
```

## API endpoints (backend)

Плагин обращается к:

| Метод | Путь | Описание |
|---|---|---|
| `POST` | `/nation-stats/internal/upsert` | Обновить статистику государства |
| `POST` | `/game-sync/nations/{slug}/membership` | Обновить состав государства |
| `GET` | `/game-sync/referrals/reward/{nick}` | Проверить и выдать реферальный бонус |

Все запросы с заголовком `X-Game-Auth-Secret`.

## Команды

| Команда | Описание |
|---|---|
| `/vrgs sync all` | Синхронизировать все государства |
| `/vrgs sync nation <slug>` | Синхронизировать одно государство |
| `/vrgs reward resolve <player>` | Проверить реферальный бонус игрока |
| `/vrgs reward apply <player>` | Применить реферальный бонус |
| `/vrgs nation set <slug> <поле> <значение>` | Вручную задать territory / bosskills / events / prestige |
| `/vrgs reload` | Перезагрузить конфиг |

**Права:** `voidrp.gamesync.admin`

## Архитектура

```
VoidRpGameSyncPlugin.java   — точка входа, регистрация задач и команд
config/
  GameSyncConfig.java       — config.yml
  NationRegistry.java       — nations.yml
service/
  BackendClient.java        — HTTP клиент к backend API
```
