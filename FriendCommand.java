package dev.amigos;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.*;
import java.util.stream.Collectors;

public class FriendCommand implements SimpleCommand {

    private static final List<String> SUBS = List.of("add", "remove", "accept", "deny", "list", "requests", "join");

    private final ProxyServer proxy;
    private final FriendManager mgr;
    private final FriendsPlugin plugin;

    public FriendCommand(ProxyServer proxy, FriendManager mgr, FriendsPlugin plugin) {
        this.proxy = proxy;
        this.mgr = mgr;
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation inv) {
        CommandSource src = inv.source();
        if (!(src instanceof Player p)) {
            src.sendMessage(Component.text("Solo jugadores."));
            return;
        }
        String[] a = inv.arguments();
        if (a.length == 0) { help(p); return; }

        String sub = a[0].toLowerCase();
        String arg = a.length > 1 ? a[1] : null;

        switch (sub) {
            case "add", "agregar", "anadir" -> { if (need(p, arg)) add(p, arg); }
            case "remove", "eliminar", "borrar" -> { if (need(p, arg)) remove(p, arg); }
            case "accept", "aceptar" -> { if (need(p, arg)) accept(p, arg); }
            case "deny", "denegar", "rechazar" -> { if (need(p, arg)) deny(p, arg); }
            case "list", "lista" -> list(p);
            case "requests", "solicitudes" -> requests(p);
            case "join", "ir" -> { if (need(p, arg)) join(p, arg); }
            default -> help(p);
        }
    }

    private boolean need(Player p, String arg) {
        if (arg == null) { p.sendMessage(Msg.error("Debes indicar un jugador.")); return false; }
        return true;
    }

    private void help(Player p) {
        p.sendMessage(Msg.info("Comandos de amigos:"));
        String[][] h = {
                {"/friend add <jugador>", "Enviar solicitud"},
                {"/friend remove <jugador>", "Eliminar amigo"},
                {"/friend accept <jugador>", "Aceptar solicitud"},
                {"/friend deny <jugador>", "Denegar solicitud"},
                {"/friend list", "Ver tus amigos"},
                {"/friend requests", "Ver solicitudes pendientes"},
                {"/friend join <jugador>", "Ir al servidor de un amigo"}
        };
        for (String[] l : h) {
            p.sendMessage(Component.text(" " + l[0], NamedTextColor.AQUA)
                    .append(Component.text(" - " + l[1], NamedTextColor.GRAY)));
        }
    }

    private void add(Player p, String name) {
        Optional<Player> opt = proxy.getPlayer(name);
        if (opt.isEmpty()) { p.sendMessage(Msg.error("Ese jugador no esta conectado.")); return; }
        Player t = opt.get();

        if (t.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(Msg.error("No puedes agregarte a ti mismo.")); return;
        }
        if (mgr.areFriends(p.getUniqueId(), t.getUniqueId())) {
            p.sendMessage(Msg.error("Ya eres amigo de " + t.getUsername() + ".")); return;
        }
        if (mgr.hasRequest(p.getUniqueId(), t.getUniqueId())) {
            p.sendMessage(Msg.error("Ya enviaste una solicitud a " + t.getUsername() + ".")); return;
        }
        if (mgr.hasRequest(t.getUniqueId(), p.getUniqueId())) {
            // Ya te habia enviado una: se acepta automaticamente
            accept(p, t.getUsername());
            return;
        }

        mgr.updateName(t.getUniqueId(), t.getUsername());
        mgr.addRequest(p.getUniqueId(), t.getUniqueId());
        p.sendMessage(Msg.ok("Solicitud enviada a " + t.getUsername() + "."));

        t.sendMessage(Msg.prefix()
                .append(Component.text(p.getUsername(), NamedTextColor.GREEN))
                .append(Component.text(" te ha enviado una solicitud de amistad. ", NamedTextColor.YELLOW))
                .append(Msg.button("Aceptar", NamedTextColor.GREEN, "/friend accept " + p.getUsername(), "Aceptar solicitud"))
                .append(Component.text(" "))
                .append(Msg.button("Denegar", NamedTextColor.RED, "/friend deny " + p.getUsername(), "Denegar solicitud")));
        t.playSound(Msg.sound(plugin.cfg("sound-request"), 1f));
    }

    private void accept(Player p, String name) {
        UUID from = mgr.findByName(name);
        if (from == null || !mgr.hasRequest(from, p.getUniqueId())) {
            p.sendMessage(Msg.error("No tienes ninguna solicitud de " + name + ".")); return;
        }
        mgr.removeRequest(from, p.getUniqueId());
        mgr.addFriends(from, p.getUniqueId());
        String fromName = mgr.name(from);
        p.sendMessage(Msg.ok("Ahora eres amigo de " + fromName + "."));
        proxy.getPlayer(from).ifPresent(o -> {
            o.sendMessage(Msg.ok(p.getUsername() + " ha aceptado tu solicitud de amistad."));
            o.playSound(Msg.sound(plugin.cfg("sound-request"), 1.5f));
        });
    }

    private void deny(Player p, String name) {
        UUID from = mgr.findByName(name);
        if (from == null || !mgr.hasRequest(from, p.getUniqueId())) {
            p.sendMessage(Msg.error("No tienes ninguna solicitud de " + name + ".")); return;
        }
        mgr.removeRequest(from, p.getUniqueId());
        p.sendMessage(Msg.ok("Has denegado la solicitud de " + mgr.name(from) + "."));
        proxy.getPlayer(from).ifPresent(o ->
                o.sendMessage(Msg.error(p.getUsername() + " ha denegado tu solicitud de amistad.")));
    }

    private void remove(Player p, String name) {
        UUID other = mgr.findByName(name);
        if (other == null || !mgr.areFriends(p.getUniqueId(), other)) {
            p.sendMessage(Msg.error(name + " no esta en tu lista de amigos.")); return;
        }
        mgr.removeFriends(p.getUniqueId(), other);
        p.sendMessage(Msg.ok("Has eliminado a " + mgr.name(other) + " de tus amigos."));
    }

    private void list(Player p) {
        Set<UUID> friends = mgr.friendsOf(p.getUniqueId());
        if (friends.isEmpty()) {
            p.sendMessage(Msg.info("Aun no tienes amigos. Usa /friend add <jugador>.")); return;
        }
        p.sendMessage(Msg.info("Tus amigos (" + friends.size() + "):"));

        // Conectados primero
        List<UUID> sorted = new ArrayList<>(friends);
        sorted.sort(Comparator.comparing((UUID u) -> proxy.getPlayer(u).isEmpty())
                .thenComparing(u -> mgr.name(u).toLowerCase()));

        for (UUID id : sorted) {
            String name = mgr.name(id);
            Optional<Player> online = proxy.getPlayer(id);
            Component line = Component.text(" - ", NamedTextColor.DARK_GRAY);
            if (online.isPresent()) {
                String server = online.get().getCurrentServer()
                        .map(s -> s.getServerInfo().getName()).orElse("?");
                line = line.append(Component.text(name, NamedTextColor.GREEN))
                        .append(Component.text(" (" + server + ") ", NamedTextColor.GRAY))
                        .append(Msg.button("Ir", NamedTextColor.AQUA, "/friend join " + name, "Ir a " + server));
            } else {
                line = line.append(Component.text(name + " (desconectado)", NamedTextColor.RED));
            }
            p.sendMessage(line);
        }
    }

    private void requests(Player p) {
        Set<UUID> reqs = mgr.requestsTo(p.getUniqueId());
        if (reqs.isEmpty()) { p.sendMessage(Msg.info("No tienes solicitudes pendientes.")); return; }
        p.sendMessage(Msg.info("Solicitudes pendientes (" + reqs.size() + "):"));
        for (UUID id : reqs) {
            String name = mgr.name(id);
            p.sendMessage(Component.text(" - " + name + " ", NamedTextColor.GREEN)
                    .append(Msg.button("Aceptar", NamedTextColor.GREEN, "/friend accept " + name, "Aceptar"))
                    .append(Component.text(" "))
                    .append(Msg.button("Denegar", NamedTextColor.RED, "/friend deny " + name, "Denegar")));
        }
    }

    private void join(Player p, String name) {
        UUID id = mgr.findByName(name);
        if (id == null || !mgr.areFriends(p.getUniqueId(), id)) {
            p.sendMessage(Msg.error(name + " no es tu amigo.")); return;
        }
        Optional<Player> friend = proxy.getPlayer(id);
        if (friend.isEmpty()) { p.sendMessage(Msg.error(name + " no esta conectado.")); return; }

        Optional<RegisteredServer> target = friend.get().getCurrentServer().map(s -> s.getServer());
        if (target.isEmpty()) { p.sendMessage(Msg.error(name + " no esta en ningun servidor.")); return; }

        String current = p.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse("");
        String targetName = target.get().getServerInfo().getName();
        if (current.equalsIgnoreCase(targetName)) {
            p.sendMessage(Msg.info("Ya estas en el mismo servidor que " + name + ".")); return;
        }

        p.sendMessage(Msg.ok("Conectando a " + targetName + "..."));
        p.createConnectionRequest(target.get()).fireAndForget();
    }

    @Override
    public List<String> suggest(Invocation inv) {
        String[] a = inv.arguments();
        if (a.length <= 1) {
            String start = a.length == 0 ? "" : a[0].toLowerCase();
            return SUBS.stream().filter(s -> s.startsWith(start)).collect(Collectors.toList());
        }
        if (a.length == 2 && !a[0].equalsIgnoreCase("list") && !a[0].equalsIgnoreCase("requests")) {
            String start = a[1].toLowerCase();
            return proxy.getAllPlayers().stream().map(Player::getUsername)
                    .filter(n -> n.toLowerCase().startsWith(start)).collect(Collectors.toList());
        }
        return List.of();
    }
}
