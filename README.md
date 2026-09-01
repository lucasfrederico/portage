# Portage

Carries a player's state between the servers of a Minecraft network.

When someone leaves server `a` and lands on server `b`, Portage makes sure `b` applies what `a` had a moment earlier: inventory, ender chest, experience, health, hunger, potion effects and game mode. Redis is the fast lane between the two servers, MariaDB keeps the history, and a checkout key guarantees that only one server owns a player at any given time.

Built for Paper and Folia 26.2. The bench in this repository runs a Velocity proxy in front of one Paper backend and one Folia backend, all in Docker, and a round trip `a → b → a` with items, XP, effects and game mode changed on each leg comes back intact.

## What travels with the player

| Carried | Left behind on purpose |
|---|---|
| Main inventory, armor, off-hand | Location (each server has its own world) |
| Ender chest | Advancements and statistics |
| Selected hotbar slot | Economy balances |
| Health, food, saturation | Anything a plugin stores about the player |
| Level and XP progress | |
| Game mode | |
| Active potion effects | |

Portage moves a player, not a world. The things on the right already have owners on a real network (the economy plugin, the stats database, the world itself), and duplicating them here would only create a second source of truth.

## How a handoff works

```
  server a, player quits                    server b, player joins
  ──────────────────────                    ──────────────────────
  capture the snapshot                      lock the player: no moving, no clicks
  SET  portage:data:<uuid>      (Redis)     SET NX portage:checkout:<uuid> = "b"
  DEL  portage:checkout:<uuid>  (Redis)         owned by someone else? poll every 50 ms
  INSERT INTO portage_snapshots (MariaDB)   GETDEL portage:data:<uuid>
                                            apply on the player's thread, unlock
```

The order on the left is deliberate. The snapshot reaches Redis before the checkout is released, so the moment `b` wins the checkout the data is already there; the database insert comes last, so `b` never waits on a disk write. `GETDEL` consumes the payload, which means a snapshot is applied at most once. When the lane holds nothing (the Redis copy expired, or this is the first join after a crash) `b` falls back to the newest row in MariaDB.

### When the previous server never lets go

If `a` crashed halfway through, its checkout is still there and `b` cannot take it. `b` waits `wait-ms` (3 s by default), logs which server was holding the player, drops the stale checkout and takes over with the archive copy. Two more guards cover the rest: checkouts carry a TTL, and every server releases the checkouts still signed with its own id when it boots, so a crash never leaves a player locked out.

### Leaving before the data arrives

A player who disconnects while still locked is released, not saved. Their inventory at that point is empty, and an empty inventory must never overwrite the real one. `HandoffProtocolTest.abandoningAWaitingPlayerReleasesWithoutWritingAnything` pins this down.

## Folia

Capture and apply run on the player's own scheduler, storage calls run on the async scheduler, and nothing assumes a main thread. The plugin declares `folia-supported: true` and was verified on Folia 26.2 build 7 as backend `b` of the bench.

## Storage

Redis, two keys per player:

| Key | Value | TTL |
|---|---|---|
| `portage:checkout:<uuid>` | id of the server that owns the player | `checkout-ttl-ms` |
| `portage:data:<uuid>` | the last snapshot handed off | `data-ttl-ms` |

MariaDB or MySQL, three tables created on first start:

```sql
CREATE TABLE portage_players (
    id         INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    uuid       BINARY(16)   NOT NULL UNIQUE,
    name       VARCHAR(16)  NOT NULL,
    first_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE portage_servers (
    id   SMALLINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE
);
CREATE TABLE portage_snapshots (
    player_id INT UNSIGNED      NOT NULL,
    id        BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    server_id SMALLINT UNSIGNED NOT NULL,
    cause     TINYINT UNSIGNED  NOT NULL,   -- 1 quit, 2 stop, 3 manual
    format    SMALLINT UNSIGNED NOT NULL,
    taken_at  TIMESTAMP(3)      NOT NULL,
    payload   MEDIUMBLOB        NOT NULL,
    PRIMARY KEY (player_id, id),
    KEY by_id (id)
);
```

A snapshot row spends 6 bytes on identity (a 4-byte player id and a 2-byte server id) instead of a 36-character UUID and a server name, which matters once the table counts its rows in billions. The primary key clusters each player's rows together, so `latest` and any future rollback read one page. Ids are resolved once per player and cached in memory; the hot path is one `INSERT` and one `DELETE`.

Every quit, every server stop and every manual save appends a row; `archive.keep-per-player` (20 by default, `0` for unlimited) prunes the player's oldest rows on each save, so the table doubles as a bounded rollback history. The payload is JSON with item bytes as base64, readable straight out of `redis-cli` or a `SELECT` while you debug. Items use Paper's own `ItemStack.serializeItemsAsBytes`, which the game upgrades across versions by itself; a `format` field versions everything else.

## Installing

Requirements: Paper or Folia 26.2, Java 25, Redis, MariaDB or MySQL, and a Velocity proxy with `bungee-plugin-message-channel = true` (the default). Drop `portage-paper-<version>.jar` into every backend, start once, then edit `plugins/Portage/config.yml`:

```yaml
server-id: "a"              # unique per backend; this is what signs checkouts

redis:
  host: "127.0.0.1"
  port: 6379
  password: ""

database:
  jdbc-url: "jdbc:mariadb://127.0.0.1:3306/portage"
  user: "root"
  password: ""

archive:
  keep-per-player: 20       # snapshots kept per player; 0 keeps everything

handoff:
  wait-ms: 3000             # how long a join waits for the previous server
  poll-ms: 50               # how often it checks
  checkout-ttl-ms: 30000    # a silent server loses its checkouts after this
  data-ttl-ms: 300000       # a handed-off snapshot waits this long to be claimed
```

Jedis, HikariCP and the MariaDB driver are fetched by the server from `plugin.yml`'s `libraries` list; the jar itself stays small.

Commands, permission `portage.admin`:

- `/portage send <player> <server>` moves a player through the proxy.
- `/portage save <player>` archives a player in place, without releasing the checkout.

Both exist so a handoff can be exercised from a console alone.

## Bench

`bench/` is the whole network in one `docker compose`: Redis, MariaDB, Velocity on `:25565`, Paper as `a` and Folia as `b`.

```sh
./gradlew build
cd bench
./fetch.sh              # downloads Paper, Folia and Velocity, copies the plugin
docker compose up -d
```

Connect a client to `localhost:25565`, then drive the test from RCON (`a` is on `25575`, `b` on `25576`, password `portagebench`):

```sh
python3 rcon.py 127.0.0.1 25575 portagebench give Steve emerald 13
python3 rcon.py 127.0.0.1 25575 portagebench portage send Steve b
python3 rcon.py 127.0.0.1 25576 portagebench portage send Steve a   # b's console, once there
python3 rcon.py 127.0.0.1 25575 portagebench data get entity Steve Inventory
docker exec portage-bench-redis-1 redis-cli keys 'portage:*'
```

Folia disables `/data`, so read the state on the way back to `a`.

## Building

```sh
./gradlew build
```

Java 25 toolchain, Gradle wrapper included. Tests cover the handoff rules against in-memory lanes (`HandoffProtocolTest`) and the snapshot codec (`SnapshotCodecTest`); the game-facing layer is thin on purpose and is exercised by the bench.

## Layout

```
portage-paper/src/main/java/dev/lucasfrederico/portage/
  data/    PlayerSnapshot, SnapshotCodec, Snapshots (capture/apply on a live player)
  store/   CheckoutLane + RedisStore, SnapshotArchive + MysqlStore
  sync/    HandoffProtocol (the rules), Handoff (scheduling), HandoffListener (the freeze)
  PortagePlugin, PortageCommand
bench/     docker compose network, seed configs, fetch script, rcon client
```

## Status

`0.1.0`. What works is described above; what is next: a periodic autosave into the archive, a `/portage rollback` that restores any archived row, and metrics on handoff latency.
