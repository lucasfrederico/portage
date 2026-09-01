package dev.lucasfrederico.portage.sync;

import dev.lucasfrederico.portage.store.CheckoutLane;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** A fast lane that lives in two maps, with the same semantics as Redis minus expiry. */
final class InMemoryLane implements CheckoutLane {

    final Map<UUID, String> checkouts = new HashMap<>();
    final Map<UUID, byte[]> snapshots = new HashMap<>();
    int renewals;

    @Override
    public boolean tryCheckout(UUID player, String server) {
        return checkouts.putIfAbsent(player, server) == null;
    }

    @Override
    public Optional<String> checkoutOwner(UUID player) {
        return Optional.ofNullable(checkouts.get(player));
    }

    @Override
    public void renewCheckout(UUID player) {
        renewals++;
    }

    @Override
    public void release(UUID player) {
        checkouts.remove(player);
    }

    @Override
    public void putSnapshot(UUID player, byte[] payload) {
        snapshots.put(player, payload);
    }

    @Override
    public Optional<byte[]> takeSnapshot(UUID player) {
        return Optional.ofNullable(snapshots.remove(player));
    }

    @Override
    public int releaseAllOf(String server) {
        var before = checkouts.size();
        checkouts.values().removeIf(server::equals);
        return before - checkouts.size();
    }
}
