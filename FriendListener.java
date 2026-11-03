package dev.amigos;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class FriendListener {

    private final ProxyServer proxy;
    private final FriendManager manager;
    private final FriendsPlugin plugin;

    public FriendListener(ProxyServer proxy, FriendManager manager, FriendsPlugin plugin) {
        this.proxy = proxy;
        this.manager = manager;
        this.plugin = plugin;
    }

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        Player p = event.getPlayer();
        manager.updateName(p.getUniqueId(), p.getUsername());

        int pending = manager.requestsTo(p.getUniqueId()).size();
        if (pending > 0) {
            p.sendMessage(Msg.info("Tienes " + pending + " solicitud(es) de amistad pendiente(s). ")
                    .append(Msg.button("Ver", NamedTextColor.AQUA, "/friend requests", "Ver solicitudes")));
        }
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player joined = event.getPlayer();
        Optional<String> current = joined.getCurrentServer()
                .map(s -> s.getServerInfo().getName());
        if (current.isEmpty()) return;

        String lobby = plugin.cfg("lobby-server");
        if (!current.get().equalsIgnoreCase(lobby)) return;

        boolean everyVisit = Boolean.parseBoolean(plugin.cfg("notify-on-every-lobby-visit"));
        if (!everyVisit && event.getPreviousServer() != null) return;

        Set<String> targets = new HashSet<>();
        for (String s : plugin.cfg("notify-on-servers").split(",")) {
            if (!s.isBlank()) targets.add(s.trim().toLowerCase());
        }

        for (UUID friendId : manager.friendsOf(joined.getUniqueId())) {
            Optional<Player> opt = proxy.getPlayer(friendId);
            if (opt.isEmpty()) continue;
            Player friend = opt.get();

            Optional<String> friendServer = friend.getCurrentServer()
                    .map(s -> s.getServerInfo().getName().toLowerCase());
            if (friendServer.isEmpty() || !targets.contains(friendServer.get())) continue;

            Component msg = Msg.prefix()
                    .append(Component.text(joined.getUsername(), NamedTextColor.GREEN))
                    .append(Component.text(" se ha conectado al Lobby. ", NamedTextColor.YELLOW))
                    .append(Msg.button("Ir al Lobby", NamedTextColor.AQUA,
                            "/friend join " + joined.getUsername(),
                            "Click para ir con " + joined.getUsername()));
            friend.sendMessage(msg);
            friend.playSound(Msg.sound(plugin.cfg("sound-join"), 1.2f));
        }
    }
}
