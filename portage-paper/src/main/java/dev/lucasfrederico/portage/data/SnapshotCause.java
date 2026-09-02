package dev.lucasfrederico.portage.data;

/**
 * Why a snapshot was archived. Stored as its {@link #code()} so a row costs
 * one byte for it.
 */
public enum SnapshotCause {

    /** The player left this server. */
    QUIT(1),
    /** This server stopped with the player online. */
    STOP(2),
    /** Staff or a scheduled task asked for it. */
    MANUAL(3),
    /** An archived snapshot was restored over the player's state. */
    ROLLBACK(4);

    private final int code;

    SnapshotCause(int code) {
        this.code = code;
    }

    /**
     * The stable number this cause is stored as.
     *
     * @return the code, 1 to 255
     */
    public int code() {
        return code;
    }
}
