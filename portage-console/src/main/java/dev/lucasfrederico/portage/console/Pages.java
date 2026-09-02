package dev.lucasfrederico.portage.console;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Server-rendered HTML. One shared layout, plain CSS, and a few lines of
 * script that poll the fragment endpoints; nothing to build or bundle.
 */
final class Pages {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private static final String[] CAUSES = {"?", "quit", "stop", "manual", "rollback"};

    private Pages() {
    }

    static String live() {
        return layout("Live", "/", """
                <div id="poll" data-src="/fragments/live"></div>
                """ + pollScript());
    }

    static String liveFragment(Bus bus) {
        var html = new StringBuilder();
        var checkouts = bus.checkouts();
        html.append("<h2>Open checkouts <span>").append(checkouts.size()).append("</span></h2>");
        if (checkouts.isEmpty()) {
            html.append("<div class=panel><p class=empty>No server owns a player"
                    + " right now.</p></div>");
        } else {
            html.append("<div class=panel><table><tr><th>Player</th><th>Owned by</th></tr>");
            checkouts.forEach((uuid, owner) -> html.append("<tr><td class=mono><a href=\"/player/")
                    .append(uuid).append("\">").append(uuid).append("</a></td><td>")
                    .append(escape(owner)).append("</td></tr>"));
            html.append("</table></div>");
        }
        var events = bus.recentEvents(50);
        html.append("<h2>Events</h2>");
        if (events.isEmpty()) {
            html.append("<div class=panel><p class=empty>No events since the console started."
                    + " Join or leave any server and they show up here.</p></div>");
        } else {
            html.append("<div class=panel><table><tr><th>Time</th><th>Event</th><th>Player</th>"
                    + "<th>Server</th><th>Detail</th></tr>");
            for (JsonObject event : events) {
                html.append(eventRow(event));
            }
            html.append("</table></div>");
        }
        return html.toString();
    }

    private static String eventRow(JsonObject event) {
        var type = text(event, "type");
        var detail = switch (type) {
            case "applied" -> "from " + text(event, "source") + ", owned in "
                    + text(event, "waitedMs") + " ms, playing in " + text(event, "totalMs") + " ms";
            case "handoff" -> text(event, "cause") + ", " + text(event, "bytes") + " bytes";
            case "takeover" -> "\"" + text(event, "holder") + "\" never let go";
            case "rollback" -> "restored snapshot #" + text(event, "snapshotId");
            case "abandon" -> "left before the data arrived; nothing written";
            default -> "";
        };
        return "<tr><td class=\"mono dim\">" + TIME.format(Instant.ofEpochMilli(
                        event.get("at").getAsLong())) + "</td>"
                + "<td><span class=\"tag " + escape(type) + "\">" + escape(type) + "</span></td>"
                + "<td><a href=\"/player/" + escape(text(event, "player")) + "\">"
                + escape(text(event, "name")) + "</a></td>"
                + "<td>" + escape(text(event, "server")) + "</td>"
                + "<td class=dim>" + escape(detail) + "</td></tr>";
    }

    static String players(Db db, String query) {
        var html = new StringBuilder("""
                <form method=get action=/players class=search>
                  <input type=text name=q placeholder="name or uuid" value="%s" autofocus>
                  <button type=submit>Search</button>
                </form>
                """.formatted(escape(query == null ? "" : query)));
        var rows = db.players(query);
        if (rows.isEmpty()) {
            html.append("<div class=panel><p class=empty>Nothing matches. A player appears"
                    + " here after their first snapshot.</p></div>");
        } else {
            html.append("<div class=panel><table><tr><th>Player</th><th>UUID</th>"
                    + "<th>First seen</th><th>Last seen</th></tr>");
            for (Db.PlayerRow row : rows) {
                html.append("<tr><td><a href=\"/player/").append(row.uuid()).append("\">")
                        .append(escape(row.name())).append("</a></td><td class=\"mono dim\">")
                        .append(row.uuid()).append("</td><td class=dim>")
                        .append(escape(row.firstSeen())).append("</td><td class=dim>")
                        .append(escape(row.lastSeen())).append("</td></tr>");
            }
            html.append("</table></div>");
        }
        return layout("Players", "/players", html.toString());
    }

    static String player(Db db, Bus bus, String uuid, String message) {
        var html = new StringBuilder();
        if (message != null && !message.isBlank()) {
            html.append("<p class=notice>").append(escape(message)).append("</p>");
        }
        var known = db.players(uuid);
        var name = known.isEmpty() ? "Unknown player" : known.getFirst().name();
        var owner = bus.checkoutOwner(uuid);
        html.append("<h1>").append(escape(name)).append("</h1>")
                .append("<p class=subtitle><span class=mono>").append(escape(uuid))
                .append("</span>")
                .append(owner.map(s -> " &middot; currently on <b>" + escape(s) + "</b>")
                        .orElse(" &middot; offline")).append("</p>");
        var rows = db.timeline(uuid);
        if (rows.isEmpty()) {
            html.append("<div class=panel><p class=empty>No snapshots archived for this"
                    + " player yet.</p></div>");
        } else {
            html.append("<h2>Snapshots <span>newest first</span></h2>");
            html.append("<div class=panel><table><tr><th>#</th><th>Taken at</th><th>Server</th>"
                    + "<th>Cause</th><th>Mode</th><th>Level</th><th>Health</th><th>Food</th>"
                    + "<th>Effects</th><th>Size</th><th></th></tr>");
            for (Db.SnapshotRow row : rows) {
                var cause = row.cause() >= 0 && row.cause() < CAUSES.length
                        ? CAUSES[row.cause()] : String.valueOf(row.cause());
                html.append("<tr><td class=mono>").append(row.id())
                        .append("</td><td class=dim>").append(escape(row.takenAt()))
                        .append("</td><td>").append(escape(row.server()))
                        .append("</td><td><span class=\"tag ").append(cause).append("\">")
                        .append(cause).append("</span>")
                        .append("</td><td>").append(escape(row.gameMode()
                                .toLowerCase(Locale.ROOT)))
                        .append("</td><td class=mono>").append(row.level())
                        .append("</td><td class=mono>").append(row.health())
                        .append("</td><td class=mono>").append(row.food())
                        .append("</td><td class=mono>").append(row.effects())
                        .append("</td><td class=\"mono dim\">").append(row.bytes()).append(" B")
                        .append("</td><td><form method=post action=\"/player/").append(escape(uuid))
                        .append("/restore/").append(row.id())
                        .append("\"><button type=submit>Restore</button></form></td></tr>");
            }
            html.append("</table></div>");
        }
        return layout(name, "/players", html.toString());
    }

    static String servers() {
        return layout("Servers", "/servers", """
                <div id="poll" data-src="/fragments/servers"></div>
                """ + pollScript());
    }

    static String serversFragment(Bus bus) {
        var heartbeats = bus.heartbeats();
        var checkouts = bus.checkouts();
        if (heartbeats.isEmpty()) {
            return "<div class=panel><p class=empty>No server has sent a heartbeat in the"
                    + " last 15 seconds.</p></div>";
        }
        var html = new StringBuilder("<div class=cards>");
        heartbeats.forEach((server, beat) -> {
            var owned = checkouts.values().stream().filter(server::equals).count();
            var age = (System.currentTimeMillis() - beat.get("at").getAsLong()) / 1000;
            html.append("<div class=card><h3><span class=dot></span>").append(escape(server))
                    .append("</h3><div class=stats><div><div class=big>")
                    .append(text(beat, "players"))
                    .append("</div><div class=label>players</div></div><div><div class=big>")
                    .append(owned)
                    .append("</div><div class=label>checkouts</div></div></div>")
                    .append("<p class=dim>heartbeat ").append(age).append("s ago</p></div>");
        });
        return html.append("</div>").toString();
    }

    private static String text(JsonObject event, String field) {
        return event.has(field) ? event.get(field).getAsString() : "";
    }

    private static String pollScript() {
        return """
                <script>
                const el = document.getElementById('poll');
                async function tick() {
                  try {
                    const r = await fetch(el.dataset.src);
                    el.innerHTML = await r.text();
                  } catch (e) { /* server briefly away; keep polling */ }
                }
                tick();
                setInterval(tick, 2000);
                </script>
                """;
    }

    private static final String MARK = """
            <svg width="17" height="17" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M3 18c3-2.2 6 2.2 9 0s6 2.2 9 0" stroke="#6d8dff" stroke-width="2.2"
                    stroke-linecap="round"/>
              <path d="M8 10.5 12 6l4 4.5" stroke="#fff" stroke-width="2.2"
                    stroke-linecap="round" stroke-linejoin="round"/>
              <path d="M12 6.8V14" stroke="#fff" stroke-width="2.2" stroke-linecap="round"/>
            </svg>
            """;

    private static String layout(String title, String active, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s - Portage</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600&family=IBM+Plex+Mono&display=swap" rel="stylesheet">
                <style>
                * { box-sizing: border-box; }
                body { margin: 0; background: #f2f4f7; color: #1a2433;
                       font: 14px/1.5 "IBM Plex Sans", system-ui, sans-serif; }
                header { background: #16233a; display: flex; align-items: center;
                         gap: 1.8rem; padding: .6rem 1.4rem; }
                .brand { display: flex; align-items: center; gap: .5rem; color: #fff;
                         font-weight: 600; font-size: 15px; }
                nav { display: flex; gap: 1.1rem; }
                nav a { color: #97a3b8; text-decoration: none; font-weight: 500;
                        padding: .2rem 0; border-bottom: 2px solid transparent; }
                nav a:hover { color: #d3dbe8; }
                nav a.on { color: #fff; border-bottom-color: #6d8dff; }
                main { max-width: 70rem; margin: 0 auto; padding: 1.3rem 1.4rem 4rem; }
                h1 { font-size: 20px; font-weight: 600; margin: .6rem 0 .1rem; }
                .subtitle { margin: 0 0 1rem; color: #667085; }
                .subtitle b { color: #1a2433; font-weight: 600; }
                h2 { font-size: 14px; font-weight: 600; margin: 1.5rem 0 .55rem; }
                h2 span { color: #667085; font-weight: 400; }
                .panel { background: #fff; border: 1px solid #e3e7ec; border-radius: 6px;
                         box-shadow: 0 1px 2px rgba(16,24,40,.05); overflow-x: auto; }
                table { width: 100%%; border-collapse: collapse; font-size: 13.5px; }
                th { text-align: left; padding: .5rem .9rem; color: #667085;
                     font-weight: 600; font-size: 12.5px; background: #f8fafc;
                     border-bottom: 1px solid #e3e7ec; white-space: nowrap; }
                td { padding: .45rem .9rem; border-bottom: 1px solid #eef1f4; }
                tr:last-child td { border-bottom: 0; }
                tr:hover td { background: #f8fafc; }
                a { color: #3454d1; text-decoration: none; font-weight: 500; }
                a:hover { text-decoration: underline; }
                .mono { font-family: "IBM Plex Mono", ui-monospace, monospace;
                        font-size: 12.5px; font-variant-numeric: tabular-nums; }
                .dim { color: #667085; }
                .tag { display: inline-block; padding: .05rem .55rem; border-radius: 999px;
                       font-size: 12px; font-weight: 500; background: #eef1f4; color: #3a4557; }
                .tag.applied { background: #e7f5ee; color: #12805c; }
                .tag.rollback { background: #f0ebfb; color: #6941c6; }
                .tag.handoff, .tag.manual { background: #e9eefb; color: #3454d1; }
                .tag.takeover, .tag.abandon, .tag.stop { background: #fbeae9; color: #b42318; }
                .empty { color: #667085; padding: 1.1rem .9rem; margin: 0; }
                .notice { background: #ecfdf3; border: 1px solid #abefc6; color: #067647;
                          border-radius: 6px; padding: .55rem .9rem; margin: 0 0 1rem; }
                .search { margin: .4rem 0 1.1rem; display: flex; gap: .5rem; }
                input[type=text] { flex: 0 1 22rem; padding: .45rem .7rem; font: inherit;
                       border: 1px solid #cfd6df; border-radius: 6px; }
                input[type=text]:focus { outline: 2px solid #c9d4f6; border-color: #3454d1; }
                button { font: inherit; font-weight: 500; padding: .4rem .95rem;
                         border: 1px solid #cfd6df; border-radius: 6px; background: #fff;
                         color: #1a2433; cursor: pointer; }
                button:hover { border-color: #3454d1; color: #3454d1; }
                table button { padding: .12rem .6rem; font-size: 12.5px; }
                .cards { display: grid; gap: 1rem;
                         grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr)); }
                .card { background: #fff; border: 1px solid #e3e7ec; border-radius: 6px;
                        box-shadow: 0 1px 2px rgba(16,24,40,.05); padding: 1rem 1.1rem; }
                .card h3 { margin: 0 0 .7rem; font-size: 15px; font-weight: 600;
                           display: flex; align-items: center; gap: .5rem; }
                .dot { width: 8px; height: 8px; border-radius: 50%%; background: #12b76a; }
                .stats { display: flex; gap: 2rem; margin-bottom: .4rem; }
                .big { font-size: 22px; font-weight: 600; font-variant-numeric: tabular-nums; }
                .label { color: #667085; font-size: 12.5px; }
                .card p { margin: .2rem 0 0; font-size: 12.5px; }
                </style>
                </head>
                <body>
                <header>
                  <span class="brand">%sPortage</span>
                  <nav>
                    <a href="/"%s>Live</a>
                    <a href="/players"%s>Players</a>
                    <a href="/servers"%s>Servers</a>
                  </nav>
                </header>
                <main>%s</main>
                </body>
                </html>
                """.formatted(escape(title), MARK,
                active.equals("/") ? " class=on" : "",
                active.equals("/players") ? " class=on" : "",
                active.equals("/servers") ? " class=on" : "",
                body);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
