# 🔌 VoidRP Game Sync Plugin

> Paper 1.21.1 плагин — синхронизация игровых данных с backend, прокси-шоп модовых предметов, динамические цены, рынок игроков.

![Paper](https://img.shields.io/badge/Paper-1.21.1-00AF54?logo=data:image/svg+xml;base64,)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Shadow-02303A?logo=gradle)
![Vault](https://img.shields.io/badge/depends-Vault-yellow)
![License](https://img.shields.io/badge/license-proprietary-red)

---

## 🗺️ Место в экосистеме

```
  Minecraft Server (Mohist 1.21.1)
  └── voidrp-gamesync-plugin
        │ X-Game-Auth-Secret (HTTP)
        ▼
  minecraft-backend (FastAPI)
        │
        ├── нации · статистика · рефералы
        ├── рынок игроков (ордера, доставки)
        └── экономика (цены предметов)

  ESGUI (магазин) ──► EconomyShopGuiBridgeService (перехват транзакций)
```

---

## ✨ Возможности

### Синхронизация данных
- Статистика нации (онлайн, баланс, территория) → backend
- Членство и роли → LuckPerms meta (`nation`, `nation_role`)
- Dynmap маркеры наций и цвета WorldGuard регионов

### Прокси-шоп модовых предметов
- `EconomyShopGuiBridgeService` — перехватывает транзакции ESGUI для модовых предметов
- Выдаёт модовые предметы через `minecraft:give` (поддержка data components)
- Динамические цены из backend через `EconomyMarketCache`
- `ModdedShopItemFixupService` — reflection-патч ESGUI для корректного отображения иконок

### Рынок игроков
- Ордера на покупку/продажу между игроками
- Delivery система — безопасная выдача после подтверждения (ack before deliver)
- Комиссия 2% (1% для Premium игроков), 0.5% за отмену
- Сериализация ItemStack через NBT (работает с модовыми предметами)
- `PlayerMarketTradeEvent` — интеграция с Battle Pass и квестами

### Альянсы и PvP
- `AllianceCacheService` — кэш союзников для подавления friendly-fire
- Голосование за альянсы, дипломатические статусы

---

## 📋 Требования

| Компонент | Версия |
|---|---|
| Paper / Mohist | 1.21.1 |
| Java | 21 |
| Vault | любая |
| LuckPerms | опционально |
| EconomyShopGUI | опционально |

---

## 🚀 Сборка и деплой

```bash
cd voidrp_gamesync_plugin
./gradlew shadowJar
# → build/libs/voidrp-game-sync-paper-1.4.0-all.jar

# Деплой с горячей перезагрузкой (без рестарта сервера)
cp build/libs/voidrp-game-sync-paper-*.jar \
   /path/to/minecraft_server/plugins/VoidRpGameSync.jar
mcrcon -H 127.0.0.1 -P 25575 -p <pass> "plugman reload VoidRpGameSync"
```

---

## ⚙️ Конфигурация

**`config.yml`** — URL backend, секрет, интервалы:
```yaml
backend:
  url: https://api.void-rp.ru/api/v1
  secret: <X-Game-Auth-Secret>
sync:
  interval_ticks: 6000    # каждые 5 минут
```

**`plugins/VoidRpGameSync/modded_items.yml`** — реестр модовых предметов:
```yaml
"Section.pageN.items.M":
  id: "namespace:item_id"
  display: "Название"
  buy: 10000000.0
  sell: 2500000.0
  locked: false           # true = фиксированная цена, игнорировать DB
```

---

## 🛠️ Команды

| Команда | Описание | Права |
|---|---|---|
| `/vrgs sync all` | Синхронизировать все нации | `voidrp.gamesync.admin` |
| `/vrgs sync nation <slug>` | Синхронизировать нацию | `voidrp.gamesync.admin` |
| `/vrgs reward resolve <player>` | Рассчитать награды | `voidrp.gamesync.admin` |
| `/vrgs nation set <slug> <field> <val>` | Изменить поле нации | `voidrp.gamesync.admin` |
| `/vrgs reload` | Перезагрузить конфиг | `voidrp.gamesync.admin` |

---

## 🔗 Связанные репозитории

| Репо | Связь |
|---|---|
| [minecraft-backend](https://github.com/VOIDRP-MINECRAFT/minecraft-backend) | REST API — все запросы идут сюда |
| [voidrp-battlepass](https://github.com/VOIDRP-MINECRAFT/voidrp-battlepass) | Слушает `PlayerMarketTradeEvent` для XP |
| [voidrp-daily-quests](https://github.com/VOIDRP-MINECRAFT/voidrp-daily-quests) | Слушает `PlayerMarketTradeEvent` для квестов |
| [wg-region-guard](https://github.com/VOIDRP-MINECRAFT/wg-region-guard) | WorldGuard интеграция для наций |

---

<div align="center">
<a href="https://void-rp.ru">🌐 Сайт</a> ·
<a href="https://github.com/VOIDRP-MINECRAFT">🏠 Организация</a>
</div>
