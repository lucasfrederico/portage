package dev.lucasfrederico.portage.store;

import java.util.Optional;
import java.util.UUID;

/**
 * The fast lane between servers: who owns a player right now, and the
 * snapshot the previous owner left for the next one.
 */
public interface CheckoutLane {

    /**
     * Tries to take the checkout for a player.
     *
     * @param player the player
     * @param server the server asking
     * @return {@code true} when that server now owns the player
     */
    boolean tryCheckout(UUID player, String server);

    /**
     * Who holds the checkout for a player.
     *
     * @param player the player
     * @return the owning server id, if any
     */
    Optional<String> checkoutOwner(UUID player);

    /**
     * Keeps a held checkout alive.
     *
     * @param player the player
     */
    void renewCheckout(UUID player);

    /**
     * Gives a player's checkout back.
     *
     * @param player the player
     */
    void release(UUID player);

    /**
     * Hands a snapshot off for the next server to claim.
     *
     * @param player  the player
     * @param payload the encoded snapshot
     */
    void putSnapshot(UUID player, byte[] payload);

    /**
     * Claims the snapshot handed off for a player, removing it.
     *
     * @param player the player
     * @return the encoded snapshot, if one was waiting
     */
    Optional<byte[]> takeSnapshot(UUID player);

    /**
     * Drops every checkout a server still holds.
     *
     * @param server the server id whose checkouts to drop
     * @return how many were dropped
     */
    int releaseAllOf(String server);
}
