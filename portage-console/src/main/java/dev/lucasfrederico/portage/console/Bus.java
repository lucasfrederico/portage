package dev.lucasfrederico.portage.console;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ScanParams;

/**
 * The console's view of Redis: a ring of recent events fed by the pub/sub
 * channel, the open checkouts, the server heartbeats, and the way to ask a
 * server to apply an archived snapshot.
 */
final class Bus {

    private static final String EVENTS_CHANNEL = "portage:events";
    private static final String APPLY_CHANNEL = "portage:apply";
    private static final String CHECKOUT = "portage:checkout:";
    private static final String SERVER = "portage:server:";
    private static final int RING_SIZE = 500;

    private final JedisPool pool;
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();

    Bus(String host, int port) {
        this.pool = new JedisPool(host, port);
        var listener = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                remember(message);
            }
        };
        var thread = new Thread(() -> {
            while (true) {
                try (var jedis = pool.getResource()) {
                    jedis.subscribe(listener, EVENTS_CHANNEL);
                } catch (RuntimeException e) {
                    sleepBeforeRetry();
                }
            }
        }, "console-events");
        thread.setDaemon(true);
        thread.start();
    }

    private void remember(String message) {
        JsonObject event;
        try {
            event = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        synchronized (events) {
            events.addFirst(event);
            while (events.size() > RING_SIZE) {
                events.removeLast();
            }
        }
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Newest first. */
    List<JsonObject> recentEvents(int limit) {
        synchronized (events) {
            var out = new ArrayList<JsonObject>(Math.min(limit, events.size()));
            for (JsonObject event : events) {
                out.add(event);
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        }
    }

    /** Player uuid to owning server, for every open checkout. */
    Map<String, String> checkouts() {
        var out = new LinkedHashMap<String, String>();
        try (var jedis = pool.getResource()) {
            var cursor = ScanParams.SCAN_POINTER_START;
            var params = new ScanParams().match(CHECKOUT + "*").count(200);
            do {
                var page = jedis.scan(cursor, params);
                for (String key : page.getResult()) {
                    var owner = jedis.get(key);
                    if (owner != null) {
                        out.put(key.substring(CHECKOUT.length()), owner);
                    }
                }
                cursor = page.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        }
        return out;
    }

    Optional<String> checkoutOwner(String uuid) {
        try (var jedis = pool.getResource()) {
            return Optional.ofNullable(jedis.get(CHECKOUT + uuid));
        }
    }

    /** Server id to its last heartbeat body, for every live server. */
    Map<String, JsonObject> heartbeats() {
        var out = new TreeMap<String, JsonObject>();
        try (var jedis = pool.getResource()) {
            var cursor = ScanParams.SCAN_POINTER_START;
            var params = new ScanParams().match(SERVER + "*").count(200);
            do {
                var page = jedis.scan(cursor, params);
                for (String key : page.getResult()) {
                    var body = jedis.get(key);
                    if (body != null) {
                        out.put(key.substring(SERVER.length()),
                                JsonParser.parseString(body).getAsJsonObject());
                    }
                }
                cursor = page.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        }
        return out;
    }

    /** Asks whichever server holds the player to apply an archived row. */
    void requestApply(String uuid, long snapshotId) {
        var command = new JsonObject();
        command.addProperty("player", uuid);
        command.addProperty("snapshotId", snapshotId);
        try (var jedis = pool.getResource()) {
            jedis.publish(APPLY_CHANNEL, command.toString());
        }
    }
}
