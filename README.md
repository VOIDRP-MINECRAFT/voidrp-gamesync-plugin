# 🔌 VoidRP Game Sync Plugin

> Paper 1.21.1 плагин — синхронизация игровых данных с backend, прокси-шоп модовых предметов, динамические цены, рынок игроков, WebGUI-мост.

![Paper](https://img.shields.io/badge/Paper-1.21.1-00AF54)
![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)
![Gradle](https://img.shields.io/badge/Gradle-Shadow-02303A?logo=gradle)
![Vault](https://img.shields.io/badge/depends-Vault-yellow)
![License](https://img.shields.io/badge/license-proprietary-red)

---

## 🗺️ Место в экосистеме

```
  Minecraft Server (Mohist 1.21.1)
  └── voidrp-gamesync-plugin
        │ X-Game-Auth-Secret (HTTP)         WebGUI plugin channels
        ▼                                   ▼
  minecraft-backend (FastAPI)     Minecraft Client
        │                           voidrp-webgui (MCEF Chromium)
        ├── нации · статистика              ▲
        ├── рынок игроков                   │
        └── pending web actions     webgui:open_web / webgui:set_main_menu

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
- Delivery система — безопасная выдача (ack before deliver)
- Комиссия 2% (1% Premium), 0.5% за отмену
- Сериализация ItemStack через NBT (работает с модовыми предметами)
- `PlayerMarketTradeEvent` — интеграция с Battle Pass и квестами

### WebGUI-мост (in-game UI)
- `WebGuiBridgeService` — открывает страницы сайта прямо в игре через Chromium
- `WebActionPollService` — поллер pending web actions от браузерных страниц
- `WebGuiPlayerJoinListener` — автоматически задаёт URL главного меню (F6) при входе

### Альянсы и PvP
- `AllianceCacheService` — кэш союзников для подавления friendly-fire
- Голосование за альянсы, дипломатические статусы

---

## 🌐 WebGUI Bridge

Плагин — **единственный транспорт** для отправки WebGUI-пакетов клиентам. Bukkit не может диспатчить NeoForge-команды, поэтому пакеты уходят через `player.sendPluginMessage()`.

### WebGuiBridgeService API

```java
// Открыть fullscreen GUI
webGuiBridge.openGui(player, "https://void-rp.ru/game-ui#market");

// Открыть HUD-оверлей
webGuiBridge.openHud(player, "https://void-rp.ru/game-ui/hud");

// Задать URL для клавиши F6 (главное меню)
webGuiBridge.sendMainMenuUrl(player, "https://void-rp.ru/game-ui/menu");

// Удобные методы
webGuiBridge.openMarket(player);        // /pm, /market, /shop
webGuiBridge.openNationMarket(player);  // /nmarket
webGuiBridge.openTreasury(player);      // /ntreasury
webGuiBridge.openBattlepass(player);    // /bp (кнопка в меню)
webGuiBridge.openQuests(player);        // /quests (кнопка в меню)
```

`signUrl(url)` — автоматически добавляет `?webgui_token=<HMAC-SHA256>` к URL перед отправкой. Секрет читается из `config/webgui/server.json` (тот же файл что и NeoForge мод).

### Протокол пакетов

```
Канал: webgui:open_web
Payload: VarInt(protocolVersion=1) + VarInt(mode: 0=GUI / 1=HUD) + MCString(url_with_token)

Канал: webgui:set_main_menu
Payload: MCString(url)
```

Регистрация каналов в `registerOutgoingPluginChannel` обёрнута в try-catch — NeoForge мод может зарегистрировать их первым (P1.10).

### WebActionPollService

Поллит `GET /game-sync/market-web-actions` каждую секунду. Обрабатывает действия, созданные браузерной страницей:

| action_type | Что делает |
|---|---|
| `buy` | Списывает деньги через Vault, создаёт buy order |
| `cancel_sell` | Возвращает предмет, удерживает комиссию 0.5% |
| `cancel_buy` | Возвращает деньги (за вычетом комиссии) |
| `pickup` | Выдаёт pending доставки (ack before deliver) |

`inFlight`-защита (ConcurrentHashMap) предотвращает двойную обработку при медленном backend.

### WebGuiPlayerJoinListener

При входе игрока (60-tick delay = 3 сек) отправляет `sendMainMenuUrl` с URL из `config.yml` → `webgui.urls.menu`. Delay нужен чтобы NeoForge мод успел проинициализироваться на клиенте.

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

**Важно:** после деплоя удалять старый build-output jar из `plugins/` — иначе Paper видит `Ambiguous plugin name` и загружает не тот файл (P1.8).

---

## ⚙️ Конфигурация

**`config.yml`** — URL backend, секрет, интервалы:
```yaml
backend:
  url: https://api.void-rp.ru/api/v1
  secret: <X-Game-Auth-Secret>
sync:
  interval_ticks: 6000    # каждые 5 минут
webgui:
  enabled: false           # включить когда фронтенд-страницы готовы
  urls:
    menu: https://void-rp.ru/game-ui/menu
    hud: https://void-rp.ru/game-ui/hud
    market: https://void-rp.ru/game-ui#market
    nation_market: https://void-rp.ru/game-ui#nation-market
    treasury: https://void-rp.ru/game-ui#treasury
    battlepass: https://void-rp.ru/game-ui#battlepass
    quests: https://void-rp.ru/game-ui#quests
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
| `/webgui reload` | Перечитать server.json токена | OP 2 |

---

## 🔗 Связанные репозитории

| Репо | Связь |
|---|---|
| [minecraft-backend](https://github.com/VOIDRP-MINECRAFT/minecraft-backend) | REST API — все запросы идут сюда |
| [voidrp-webgui-neoforge](https://github.com/VOIDRP-MINECRAFT/voidrp-webgui-neoforge) | NeoForge мод, который принимает наши пакеты |
| [voidrp-battlepass](https://github.com/VOIDRP-MINECRAFT/voidrp-battlepass) | Слушает `PlayerMarketTradeEvent` для XP |
| [voidrp-daily-quests](https://github.com/VOIDRP-MINECRAFT/voidrp-daily-quests) | Слушает `PlayerMarketTradeEvent` для квестов |
| [wg-region-guard](https://github.com/VOIDRP-MINECRAFT/wg-region-guard) | WorldGuard интеграция для наций |

---

<div align="center">
<a href="https://void-rp.ru">🌐 Сайт</a> ·
<a href="https://github.com/VOIDRP-MINECRAFT">🏠 Организация</a> ·
<a href="https://github.com/VOIDRP-MINECRAFT/.github/blob/main/docs/WEBGUI_ARCHITECTURE.md">📐 WebGUI Architecture</a>
</div>
