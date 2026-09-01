package dev.lucasfrederico.portage;

import dev.lucasfrederico.portage.sync.Handoff;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Staff and console commands: {@code /portage send <player> <server>} moves
 * a player through the proxy, {@code /portage save <player>} snapshots them
 * in place. Both exist so a handoff can be exercised end to end from a
 * console alone.
 */
public final class PortageCommand implements CommandExecutor {

    /** The proxy's legacy plugin channel, which Velocity keeps serving. */
    public static final String PROXY_CHANNEL = "BungeeCord";

    private final Plugin plugin;
    private final Handoff handoff;

    /**
     * Creates the command.
     *
     * @param plugin  the owning plugin
     * @param handoff the protocol runner
     */
    public PortageCommand(Plugin plugin, Handoff handoff) {
        this.plugin = plugin;
        this.handoff = handoff;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 3 && "send".equalsIgnoreCase(args[0])) {
            var target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            target.getScheduler().run(plugin, task -> connect(target, args[2]), null);
            sender.sendMessage(Component.text("Sending " + target.getName() + " to " + args[2] + ".",
                    NamedTextColor.GREEN));
            return true;
        }
        if (args.length >= 2 && "save".equalsIgnoreCase(args[0])) {
            var target = plugin.getServer().getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            target.getScheduler().run(plugin, task -> handoff.saveInPlace(target, "manual"), null);
            sender.sendMessage(Component.text("Snapshot of " + target.getName() + " requested.",
                    NamedTextColor.GREEN));
            return true;
        }
        sender.sendMessage(Component.text("/portage send <player> <server> | /portage save <player>",
                NamedTextColor.RED));
        return true;
    }

    private void connect(Player player, String server) {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeUTF("Connect");
            out.writeUTF(server);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        player.sendPluginMessage(plugin, PROXY_CHANNEL, bytes.toByteArray());
    }
}
