# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & deploy

```bash
# Build (from voidrp_gamesync_plugin/)
./gradlew shadowJar
# Output: build/libs/voidrp-game-sync-paper-1.4.0-all.jar

# Deploy + hot-reload (no server restart needed)
cp build/libs/voidrp-game-sync-paper-1.4.0-all.jar \
   /home/mironoouv/minecraft/minecraft_server/plugins/VoidRpGameSync.jar
mcrcon -H 127.0.0.1 -P 25575 -p <rcon_password> "plugman reload VoidRpGameSync"
```

The output jar version is in `build.gradle.kts` (`version = "1.4.0"`). Backend runs on port 8001; reload it with `kill -HUP <pid>`.

## Architecture overview

**Entry point:** `VoidRpGameSyncPlugin.java` — `buildServices()` manually wires all services (no DI framework); services are injected via constructors and accessed through plugin getters (e.g. `plugin.getBackendClient()`).

**Threading contract** — every `BackendClient` call is blocking and must run off the main thread:
```
runTaskAsynchronously → BackendClient call (blocks) → runTask (main thread) → Bukkit API + Vault economy ops
```
Never call `BackendClient` directly on the main thread, and never call Bukkit/Vault from the async lambda.

**BackendClient** (`service/BackendClient.java`) — Java 11 `HttpClient` + Gson. All methods throw `IOException | InterruptedException`. Errors surface as `BackendApiException` (wraps non-2xx HTTP responses). Authentication is the `X-Game-Auth-Secret` header, set automatically on every request.

**Model classes** (`model/`) — plain public fields, no getters. Gson-deserialized directly. When the backend adds a new response field, add the corresponding public field here.

## Player market system

`PlayerMarketService.java` is the core. Key invariants:

- **Ack before deliver**: `ackPlayerMarketDeliveries()` is called _before_ items/money are given to the player. This prevents double-delivery if the player disconnects mid-delivery.
- **Concurrent delivery guard**: `deliveringPlayers` (`ConcurrentHashMap.newKeySet()`) prevents two simultaneous pickup attempts for the same player.
- **Cancel fee**: `PlayerMarketCancelSellOrderResponse.cancel_fee_coins` — the plugin withdraws this from the player's Vault balance after returning items. `PlayerMarketCancelBuyOrderResponse.returned_funds` is already net of the 0.5% fee (deducted by backend).
- **Item serialization**: `ItemStackSnapshotService.serializeSingle()` uses Paper NBT format (works for mod items on Mohist) with `BukkitObjectInputStream` as a legacy fallback. Always deserialize with `ItemStackSnapshotService.deserialize(base64, amount)`.
- **Giving items**: always use `NationMarketInventoryService.giveOrDrop(player, item, amount)` — gives to inventory if space, drops at feet otherwise. Never use `addItem()` directly.

**`PlayerMarketTradeEvent`** is fired on the main thread twice per fill (once for `SELLER`, once for `BUYER`). Battle pass and daily quests plugins listen to this event for XP/quest credit. Fire it after the Vault transaction is confirmed.

**Pending delivery types** (from backend):
- `sell_proceeds` — money to seller
- `item_delivery` — item(s) to buyer
- `buy_refund` — overpay money back to buyer
- `expiry_refund` — items or money when order expires

**Pending action flow** (GUI / chat): `pendingActions` map (`UUID → PendingAction`) stores what the player is confirming. Types: `SELL`, `BUY`, `GUI_SELL`, `GUI_BUY`. Pending actions expire after a timeout; check `createdAt` before processing.

## Economy market / ESGUI bridge

- `EconomyShopGuiBridgeService` intercepts `PreTransactionEvent` from ESGUI. If the item key is in `modded_items.yml` it cancels the event and handles the transaction itself.
- `ModdedShopItemFixupService` patches ESGUI internals via reflection after every `/sreload` (fixes `itemToGive` type mismatch so sell-path `match()` works for mod items). Must re-run after each ESGUI reload.
- `EconomyShopVisualSyncService` dispatches `editshop edititem` commands to keep ESGUI display prices in sync with DB. If `pendingChangedItems >= minChanges` threshold it triggers `sreload` (which in turn triggers `scheduleFixupDelayed`).
- `EconomyMarketCache` is an in-memory snapshot of `economy_market_items` rows, refreshed by `EconomyMarketSyncService` on a configurable interval.

`modded_items.yml` key format: `"SectionName.pageN.items.M"` — SectionName = ESGUI shop file basename (no `.yml`), page/item index 1-based. `locked: true` = always use YAML base price, ignore DB cache.

## Nation / alliance systems

- `NationRegistry` loads nation definitions from the backend (`GET /game-sync/nations`) and keeps a local slug→definition map.
- `NationSyncService` pushes stats + membership snapshots; also triggers Dynmap marker updates via `DynmapMarkerService` and WorldGuard region colour updates via `DynmapWorldGuardStyleService`.
- `LuckPermsNationMetaService` writes `nation` / `nation_role` LuckPerms meta so permissions can be assigned per nation.
- `AllianceCacheService` polls `GET /game-sync/alliances/pvp-map` and keeps an in-memory map of which nations are allied. `AlliancePvpListener` uses this to suppress friendly-fire damage.

## Key backend constants (not in this repo)

These live in `minecraft_backend/.../player_market_service.py` and affect plugin behaviour:

| Constant | Value | Effect |
|---|---|---|
| `ORDER_EXPIRY_DAYS` | 7 | Orders expire after 7 days |
| `FEE_PERCENT` | 2.00% | Normal trade fee |
| `FEE_PERCENT_PREMIUM` | 1.00% | Premium player trade fee |
| `CANCEL_FEE_PERCENT` | 0.50% | Fee on order cancellation |
| `MAX_VOLUME_PER_PLAYER_PER_ITEM` | 10 000 | Max remaining_amount per player per item across active orders |
| `MAX_ACTIVE_SELL/BUY_PER_PLAYER` | 50 | Max active orders per player |
