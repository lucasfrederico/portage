package dev.lucasfrederico.portage.console;

import io.javalin.Javalin;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The Portage console: a small web panel over the same Redis and database
 * the servers use. It watches handoffs live, browses a player's snapshot
 * history, and restores any archived row, either straight onto the server
 * that holds the player or as the newest row for their next join.
 */
public final class Console {

    private Console() {
    }

    /**
     * Starts the console. Configuration comes from the environment:
     * {@code CONSOLE_PORT}, {@code REDIS_HOST}, {@code REDIS_PORT},
     * {@code DB_URL}, {@code DB_USER}, {@code DB_PASS}.
     *
     * @param args unused
     */
    public static void main(String[] args) {
        var port = Integer.parseInt(env("CONSOLE_PORT", "8090"));
        var db = new Db(env("DB_URL", "jdbc:mariadb://127.0.0.1:3306/portage"),
                env("DB_USER", "root"), env("DB_PASS", ""));
        var bus = new Bus(env("REDIS_HOST", "127.0.0.1"),
                Integer.parseInt(env("REDIS_PORT", "6379")));

        Javalin.create(config -> config.showJavalinBanner = false)
                .get("/", ctx -> ctx.html(Pages.live()))
                .get("/fragments/live", ctx -> ctx.html(Pages.liveFragment(bus)))
                .get("/players", ctx -> ctx.html(Pages.players(db, ctx.queryParam("q"))))
                .get("/player/{uuid}", ctx -> ctx.html(
                        Pages.player(db, bus, ctx.pathParam("uuid"), ctx.queryParam("msg"))))
                .post("/player/{uuid}/restore/{id}", ctx -> {
                    var uuid = ctx.pathParam("uuid");
                    var id = Long.parseLong(ctx.pathParam("id"));
                    var owner = bus.checkoutOwner(uuid);
                    String message;
                    if (owner.isPresent()) {
                        bus.requestApply(uuid, id);
                        message = "Restore of #" + id + " sent to server \"" + owner.get() + "\"";
                    } else {
                        db.copyAsRollback(uuid, id);
                        message = "#" + id + " is now the newest snapshot; it applies on next join";
                    }
                    ctx.redirect("/player/" + uuid + "?msg="
                            + URLEncoder.encode(message, StandardCharsets.UTF_8));
                })
                .get("/servers", ctx -> ctx.html(Pages.servers()))
                .get("/fragments/servers", ctx -> ctx.html(Pages.serversFragment(bus)))
                .start(port);
    }

    private static String env(String name, String fallback) {
        var value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
