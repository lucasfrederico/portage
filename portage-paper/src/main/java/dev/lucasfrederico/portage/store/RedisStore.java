package dev.lucasfrederico.portage.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;

/**
 * The fast lane between servers. Two keys per player: a checkout naming
 * the server that currently owns the player, and the last snapshot handed
 * off. A server takes the checkout on join and gives it back on quit, so
 * two servers never apply or write the same player at once.
 */
public final class RedisStore implements CheckoutLane, AutoCloseable {

    private static final String CHECKOUT = "portage:checkout:";
    private static final String DATA = "portage:data:";
    private static final String SERVER = "portage:server:";
    private static final long HEARTBEAT_TTL_MS = 15_000;

    private final JedisPool pool;
    private final List<Thread> subscribers = new ArrayList<>();
    private volatile boolean open = true;
    private final Duration checkoutTtl;
    private final Duration dataTtl;

    /**
     * Opens the pool.
     *
     * @param host        the Redis host
     * @param port        the Redis port
     * @param password    the password, or {@code null} for none
     * @param checkoutTtl how long a checkout survives a silent server
     * @param dataTtl     how long a handed-off snapshot waits to be claimed
     */
    public RedisStore(String host, int port, String password,
                      Duration checkoutTtl, Duration dataTtl) {
        this.pool = password == null || password.isBlank()
                ? new JedisPool(host, port)
                : new JedisPool(host, port, null, password);
        this.checkoutTtl = checkoutTtl;
        this.dataTtl = dataTtl;
    }

    /**
     * Tries to take the checkout for a player.
     *
     * @param player the player
     * @param server this server's id
     * @return {@code true} when this server now owns the player
     */
    @Override
    public boolean tryCheckout(UUID player, String server) {
        try (var jedis = pool.getResource()) {
            var reply = jedis.set(CHECKOUT + player, server,
                    SetParams.setParams().nx().px(checkoutTtl.toMillis()));
            return "OK".equals(reply);
        }
    }

    /**
     * Who holds the checkout for a player.
     *
     * @param player the player
     * @return the owning server id, if any
     */
    @Override
    public Optional<String> checkoutOwner(UUID player) {
        try (var jedis = pool.getResource()) {
            return Optional.ofNullable(jedis.get(CHECKOUT + player));
        }
    }

    /**
     * Keeps a checkout this server holds alive.
     *
     * @param player the player
     */
    @Override
    public void renewCheckout(UUID player) {
        try (var jedis = pool.getResource()) {
            jedis.pexpire(CHECKOUT + player, checkoutTtl.toMillis());
        }
    }

    /**
     * Gives a player's checkout back.
     *
     * @param player the player
     */
    @Override
    public void release(UUID player) {
        try (var jedis = pool.getResource()) {
            jedis.del(CHECKOUT + player);
        }
    }

    /**
     * Hands a snapshot off for the next server to claim.
     *
     * @param player  the player
     * @param payload the encoded snapshot
     */
    @Override
    public void putSnapshot(UUID player, byte[] payload) {
        try (var jedis = pool.getResource()) {
            jedis.set(key(DATA + player), payload,
                    SetParams.setParams().px(dataTtl.toMillis()));
        }
    }

    /**
     * Claims the snapshot handed off for a player, removing it.
     *
     * @param player the player
     * @return the encoded snapshot, if one was waiting
     */
    @Override
    public Optional<byte[]> takeSnapshot(UUID player) {
        try (var jedis = pool.getResource()) {
            var payload = jedis.getDel(key(DATA + player));
            return Optional.ofNullable(payload);
        }
    }

    /**
     * Drops every checkout a server still holds, used when that server
     * starts so a crash never leaves players locked out.
     *
     * @param server the server id whose checkouts to drop
     * @return how many were dropped
     */
    @Override
    public int releaseAllOf(String server) {
        var dropped = 0;
        try (var jedis = pool.getResource()) {
            var cursor = ScanParams.SCAN_POINTER_START;
            var params = new ScanParams().match(CHECKOUT + "*").count(200);
            do {
                var page = jedis.scan(cursor, params);
                for (String key : page.getResult()) {
                    if (server.equals(jedis.get(key))) {
                        jedis.del(key);
                        dropped++;
                    }
                }
                cursor = page.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        }
        return dropped;
    }

    /**
     * Publishes a message for the other servers and the console.
     *
     * @param channel the channel
     * @param message the message, JSON by convention
     */
    public void publish(String channel, String message) {
        try (var jedis = pool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    /**
     * Listens to a channel on a daemon thread, reconnecting until the store
     * closes.
     *
     * @param channel the channel
     * @param handler called with every message, on the subscriber thread
     */
    public void subscribe(String channel, Consumer<String> handler) {
        var listener = new JedisPubSub() {
            @Override
            public void onMessage(String onChannel, String message) {
                handler.accept(message);
            }
        };
        var thread = new Thread(() -> {
            while (open) {
                try (var jedis = pool.getResource()) {
                    jedis.subscribe(listener, channel);
                } catch (RuntimeException e) {
                    sleepBeforeRetry();
                }
            }
        }, "portage-subscribe-" + channel);
        thread.setDaemon(true);
        thread.start();
        subscribers.add(thread);
    }

    private static void sleepBeforeRetry() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Refreshes this server's liveness key; it expires when the server goes
     * silent.
     *
     * @param server this server's id
     * @param json   the heartbeat body
     */
    public void heartbeat(String server, String json) {
        try (var jedis = pool.getResource()) {
            jedis.set(SERVER + server, json, SetParams.setParams().px(HEARTBEAT_TTL_MS));
        }
    }

    /**
     * Checks the connection works.
     *
     * @return the PING reply
     */
    public String ping() {
        try (var jedis = pool.getResource()) {
            return jedis.ping();
        }
    }

    @Override
    public void close() {
        open = false;
        subscribers.forEach(Thread::interrupt);
        pool.close();
    }

    private static byte[] key(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
