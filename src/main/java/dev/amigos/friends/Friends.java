package dev.amigos.friends;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

@Plugin(
        id = "friends",
        name = "Friends",
        version = "1.0.0",
        description = "Sistema de amigos para el proxy",
        authors = {"amigos"}
)
public class Friends {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDir;

    // ---- configuracion ----
    private String lobbyServer = "lobby";
    private String joinSound = "entity.experience_orb.pickup";
    private String requestSound = "block.note_block.pling";

    // ---- datos (siempre acceder dentro de synchronized) ----
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<String, UUID> byName = new HashMap<>();
    private final Map<UUID, Set<UUID>> friends = new HashMap<>();
    private final Set<String> requests = new HashSet<>(); // "desde|hacia"

    @Inject
    public Friends(ProxyServer server, Logger logger, @DataDirectory Path dataDir) {
        this.server = server;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    // =====================================================================
    //  Arranque
    // =====================================================================

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            logger.error("No se pudo crear la carpeta de datos", e);
        }
        loadConfig();
        loadData();

        CommandMeta meta = server.getCommandManager()
                .metaBuilder("friend")
                .aliases("friends", "amigo", "amigos")
                .plugin(this)
                .build();
        server.getCommandManager().register(meta, new FriendCommand());
        logger.info("Friends cargado. Lobby = '{}'", lobbyServer);
    }

    private void loadConfig() {
        Path file = dataDir.resolve("config.properties");
        try {
            if (!Files.exists(file)) {
                String def = "# Nombre del servidor lobby tal como aparece en velocity.toml\n"
                        + "lobby-server=lobby\n"
                        + "# Sonido cuando un amigo entra al lobby (1.19.3+)\n"
                        + "join-sound=entity.experience_orb.pickup\n"
                        + "# Sonido cuando recibes una solicitud de amistad\n"
                        + "request-sound=block.note_block.pling\n";
                Files.writeString(file, def, StandardCharsets.UTF_8);
            }
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
            lobbyServer = p.getProperty("lobby-server", lobbyServer).trim();
            joinSound = p.getProperty("join-sound", joinSound).trim();
            requestSound = p.getProperty("request-sound", requestSound).trim();
        } catch (IOException e) {
            logger.error("No se pudo leer config.properties", e);
        }
    }

    private synchronized void loadData() {
        Path file = dataDir.resolve("data.txt");
        if (!Files.exists(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(" ");
                try {
                    if (p.length == 3 && p[0].equals("N")) {
                        UUID id = UUID.fromString(p[1]);
                        names.put(id, p[2]);
                        byName.put(p[2].toLowerCase(Locale.ROOT), id);
                    } else if (p.length == 3 && p[0].equals("F")) {
                        UUID a = UUID.fromString(p[1]);
                        UUID b = UUID.fromString(p[2]);
                        friends.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                        friends.computeIfAbsent(b, k -> new HashSet<>()).add(a);
                    } else if (p.length == 3 && p[0].equals("R")) {
                        requests.add(p[1] + "|" + p[2]);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (IOException e) {
            logger.error("No se pudo leer data.txt", e);
        }
    }

    private synchronized void saveData() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            out.add("N " + e.getKey() + " " + e.getValue());
        }
        for (Map.Entry<UUID, Set<UUID>> e : friends.entrySet()) {
            for (UUID other : e.getValue()) {
                if (e.getKey().compareTo(other) < 0) out.add("F " + e.getKey() + " " + other);
            }
        }
        for (String r : requests) {
            out.add("R " + r.replace('|', ' '));
        }
        try {
            Files.write(dataDir.resolve("data.txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("No se pudo guardar data.txt", e);
        }
    }

    // =====================================================================
    //  Eventos
    // =====================================================================

    @Subscribe
    public void onLogin(PostLoginEvent event) {
        Player p = event.getPlayer();
        boolean changed;
        synchronized (this) {
            String old = names.put(p.getUniqueId(), p.getUsername());
            if (old != null) byName.remove(old.toLowerCase(Locale.ROOT));
            byName.put(p.getUsername().toLowerCase(Locale.ROOT), p.getUniqueId());
            changed = !p.getUsername().equals(old);
            if (changed) saveData();
        }
        // Avisar de solicitudes pendientes
        int pending;
        synchronized (this) {
            pending = incoming(p.getUniqueId()).size();
        }
        if (pending > 0) {
            p.sendMessage(prefix().append(Component.text("Tienes " + pending + " solicitud(es) de amistad pendiente(s). ", NamedTextColor.YELLOW))
                    .append(button("Ver", NamedTextColor.AQUA, "/friend requests", "Ver solicitudes")));
        }
    }

    @Subscribe
    public void onServerConnected(ServerPostConnectEvent event) {
        Player joined = event.getPlayer();
        Optional<ServerConnection> cur = joined.getCurrentServer();
        if (cur.isEmpty()) return;
        String serverName = cur.get().getServerInfo().getName();
        if (!serverName.equalsIgnoreCase(lobbyServer)) return;

        RegisteredServer prev = event.getPreviousServer();
        if (prev != null && prev.getServerInfo().getName().equalsIgnoreCase(lobbyServer)) return;

        Set<UUID> myFriends;
        synchronized (this) {
            myFriends = new HashSet<>(friends.getOrDefault(joined.getUniqueId(), Set.of()));
        }
        for (UUID id : myFriends) {
            server.getPlayer(id).ifPresent(friend -> {
                // Si ya esta en el mismo servidor, no hace falta avisar
                boolean sameServer = friend.getCurrentServer()
                        .map(c -> c.getServerInfo().getName().equalsIgnoreCase(lobbyServer))
                        .orElse(false);
                if (sameServer) return;
                friend.sendMessage(prefix()
                        .append(Component.text(joined.getUsername(), NamedTextColor.GREEN, TextDecoration.BOLD))
                        .append(Component.text(" ha entrado al " + lobbyServer + ". ", NamedTextColor.YELLOW))
                        .append(button("Ir con él", NamedTextColor.GREEN, "/friend join " + joined.getUsername(),
                                "Click para ir con " + joined.getUsername())));
                playSound(friend, joinSound, 1.2f);
            });
        }
    }

    // =====================================================================
    //  Utilidades de texto y sonido
    // =====================================================================

    private static Component prefix() {
        return Component.text("[Amigos] ", NamedTextColor.GOLD);
    }

    private static Component button(String label, NamedTextColor color, String command, String hover) {
        return Component.text("[" + label + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    private static void say(Audience a, String text, NamedTextColor color) {
        a.sendMessage(prefix().append(Component.text(text, color)));
    }

    private void playSound(Player p, String key, float pitch) {
        try {
            // Velocity solo permite sonidos con emisor "self" y en clientes 1.19.3+
            p.playSound(Sound.sound(Key.key(key), Sound.Source.MASTER, 1.0f, pitch), Sound.Emitter.self());
        } catch (Exception ignored) {
        }
    }

    // =====================================================================
    //  Logica de datos (llamar dentro de synchronized)
    // =====================================================================

    private record Ref(UUID id, String name) {}

    private static String key(UUID from, UUID to) {
        return from + "|" + to;
    }

    private synchronized Ref resolve(String input) {
        Optional<Player> online = server.getPlayer(input);
        if (online.isPresent()) return new Ref(online.get().getUniqueId(), online.get().getUsername());
        UUID id = byName.get(input.toLowerCase(Locale.ROOT));
        if (id == null) return null;
        return new Ref(id, names.getOrDefault(id, input));
    }

    private synchronized String nameOf(UUID id) {
        return names.getOrDefault(id, id.toString().substring(0, 8));
    }

    private boolean isFriend(UUID a, UUID b) {
        return friends.getOrDefault(a, Set.of()).contains(b);
    }

    private List<UUID> incoming(UUID to) {
        List<UUID> out = new ArrayList<>();
        for (String r : requests) {
            String[] p = r.split("\\|");
            if (p.length == 2 && p[1].equals(to.toString())) out.add(UUID.fromString(p[0]));
        }
        return out;
    }

    private List<UUID> outgoing(UUID from) {
        List<UUID> out = new ArrayList<>();
        for (String r : requests) {
            String[] p = r.split("\\|");
            if (p.length == 2 && p[0].equals(from.toString())) out.add(UUID.fromString(p[1]));
        }
        return out;
    }

    private void makeFriends(UUID a, UUID b) {
        friends.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        friends.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        requests.remove(key(a, b));
        requests.remove(key(b, a));
    }

    // =====================================================================
    //  Comando /friend
    // =====================================================================

    private final class FriendCommand implements SimpleCommand {

        private static final List<String> SUBS =
                List.of("add", "remove", "accept", "deny", "list", "requests");

        @Override
        public void execute(Invocation inv) {
            CommandSource src = inv.source();
            if (!(src instanceof Player me)) {
                src.sendMessage(Component.text("Solo los jugadores pueden usar este comando."));
                return;
            }
            String[] a = inv.arguments();
            if (a.length == 0) {
                help(me);
                return;
            }
            String sub = a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "list" -> list(me);
                case "requests" -> showRequests(me);
                case "add", "remove", "accept", "deny", "join" -> {
                    if (a.length < 2) {
                        say(me, "Uso: /friend " + sub + " <jugador>", NamedTextColor.RED);
                        return;
                    }
                    switch (sub) {
                        case "add" -> add(me, a[1]);
                        case "remove" -> remove(me, a[1]);
                        case "accept" -> accept(me, a[1]);
                        case "deny" -> deny(me, a[1]);
                        default -> join(me, a[1]);
                    }
                }
                default -> help(me);
            }
        }

        @Override
        public List<String> suggest(Invocation inv) {
            String[] a = inv.arguments();
            List<String> out = new ArrayList<>();
            if (a.length <= 1) {
                String pre = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
                for (String s : SUBS) if (s.startsWith(pre)) out.add(s);
                return out;
            }
            String pre = a[1].toLowerCase(Locale.ROOT);
            for (Player p : server.getAllPlayers()) {
                if (p.getUsername().toLowerCase(Locale.ROOT).startsWith(pre)) out.add(p.getUsername());
            }
            if (inv.source() instanceof Player me) {
                synchronized (Friends.this) {
                    for (UUID id : friends.getOrDefault(me.getUniqueId(), Set.of())) {
                        String n = nameOf(id);
                        if (n.toLowerCase(Locale.ROOT).startsWith(pre) && !out.contains(n)) out.add(n);
                    }
                }
            }
            return out;
        }

        private void help(Player me) {
            me.sendMessage(Component.text("--- Amigos ---", NamedTextColor.GOLD, TextDecoration.BOLD));
            String[][] h = {
                    {"/friend add <jugador>", "Enviar solicitud"},
                    {"/friend remove <jugador>", "Eliminar amigo"},
                    {"/friend accept <jugador>", "Aceptar solicitud"},
                    {"/friend deny <jugador>", "Rechazar solicitud"},
                    {"/friend list", "Ver tus amigos"},
                    {"/friend requests", "Ver solicitudes pendientes"}
            };
            for (String[] l : h) {
                me.sendMessage(Component.text(l[0], NamedTextColor.YELLOW)
                        .append(Component.text(" - " + l[1], NamedTextColor.GRAY)));
            }
        }

        // ---------------- add ----------------
        private void add(Player me, String input) {
            Ref t = resolve(input);
            if (t == null) {
                say(me, "No conozco a ese jugador. Tiene que haber entrado al servidor alguna vez.", NamedTextColor.RED);
                return;
            }
            if (t.id().equals(me.getUniqueId())) {
                say(me, "No puedes agregarte a ti mismo.", NamedTextColor.RED);
                return;
            }
            int result; // 0 = solicitud enviada, 1 = ya amigos, 2 = ya enviada, 3 = aceptada automaticamente
            synchronized (Friends.this) {
                if (isFriend(me.getUniqueId(), t.id())) {
                    result = 1;
                } else if (requests.contains(key(t.id(), me.getUniqueId()))) {
                    makeFriends(me.getUniqueId(), t.id());
                    saveData();
                    result = 3;
                } else if (!requests.add(key(me.getUniqueId(), t.id()))) {
                    result = 2;
                } else {
                    saveData();
                    result = 0;
                }
            }
            switch (result) {
                case 1 -> say(me, "Ya eres amigo de " + t.name() + ".", NamedTextColor.YELLOW);
                case 2 -> say(me, "Ya le enviaste una solicitud a " + t.name() + ".", NamedTextColor.YELLOW);
                case 3 -> {
                    say(me, "¡Ahora eres amigo de " + t.name() + "!", NamedTextColor.GREEN);
                    server.getPlayer(t.id()).ifPresent(o ->
                            say(o, me.getUsername() + " aceptó tu solicitud. ¡Ahora sois amigos!", NamedTextColor.GREEN));
                }
                default -> {
                    say(me, "Solicitud enviada a " + t.name() + ".", NamedTextColor.GREEN);
                    server.getPlayer(t.id()).ifPresent(o -> {
                        o.sendMessage(prefix()
                                .append(Component.text(me.getUsername(), NamedTextColor.GREEN, TextDecoration.BOLD))
                                .append(Component.text(" quiere ser tu amigo. ", NamedTextColor.YELLOW))
                                .append(button("Aceptar", NamedTextColor.GREEN, "/friend accept " + me.getUsername(), "Aceptar solicitud"))
                                .append(Component.text(" "))
                                .append(button("Rechazar", NamedTextColor.RED, "/friend deny " + me.getUsername(), "Rechazar solicitud")));
                        playSound(o, requestSound, 1.0f);
                    });
                }
            }
        }

        // ---------------- accept ----------------
        private void accept(Player me, String input) {
            Ref t = resolve(input);
            boolean ok = false;
            if (t != null) {
                synchronized (Friends.this) {
                    if (requests.contains(key(t.id(), me.getUniqueId()))) {
                        makeFriends(me.getUniqueId(), t.id());
                        saveData();
                        ok = true;
                    }
                }
            }
            if (!ok) {
                say(me, "No tienes ninguna solicitud de ese jugador.", NamedTextColor.RED);
                return;
            }
            say(me, "¡Ahora eres amigo de " + t.name() + "!", NamedTextColor.GREEN);
            server.getPlayer(t.id()).ifPresent(o ->
                    say(o, me.getUsername() + " aceptó tu solicitud. ¡Ahora sois amigos!", NamedTextColor.GREEN));
        }

        // ---------------- deny ----------------
        private void deny(Player me, String input) {
            Ref t = resolve(input);
            boolean ok = false;
            if (t != null) {
                synchronized (Friends.this) {
                    ok = requests.remove(key(t.id(), me.getUniqueId()));
                    if (ok) saveData();
                }
            }
            if (!ok) {
                say(me, "No tienes ninguna solicitud de ese jugador.", NamedTextColor.RED);
                return;
            }
            say(me, "Solicitud de " + t.name() + " rechazada.", NamedTextColor.YELLOW);
        }

        // ---------------- remove ----------------
        private void remove(Player me, String input) {
            Ref t = resolve(input);
            boolean ok = false;
            if (t != null) {
                synchronized (Friends.this) {
                    if (isFriend(me.getUniqueId(), t.id())) {
                        friends.getOrDefault(me.getUniqueId(), new HashSet<>()).remove(t.id());
                        friends.getOrDefault(t.id(), new HashSet<>()).remove(me.getUniqueId());
                        saveData();
                        ok = true;
                    }
                }
            }
            if (!ok) {
                say(me, "Ese jugador no está en tu lista de amigos.", NamedTextColor.RED);
                return;
            }
            say(me, t.name() + " ya no es tu amigo.", NamedTextColor.YELLOW);
        }

        // ---------------- list ----------------
        private void list(Player me) {
            List<UUID> ids;
            synchronized (Friends.this) {
                ids = new ArrayList<>(friends.getOrDefault(me.getUniqueId(), Set.of()));
            }
            if (ids.isEmpty()) {
                say(me, "Aún no tienes amigos. Usa /friend add <jugador>.", NamedTextColor.YELLOW);
                return;
            }
            me.sendMessage(Component.text("--- Tus amigos (" + ids.size() + ") ---", NamedTextColor.GOLD, TextDecoration.BOLD));
            List<UUID> offline = new ArrayList<>();
            for (UUID id : ids) {
                Optional<Player> op = server.getPlayer(id);
                if (op.isEmpty()) {
                    offline.add(id);
                    continue;
                }
                Player f = op.get();
                String where = f.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse("?");
                me.sendMessage(Component.text("● ", NamedTextColor.GREEN)
                        .append(Component.text(f.getUsername(), NamedTextColor.GREEN))
                        .append(Component.text(" - " + where + " ", NamedTextColor.GRAY))
                        .append(button("Ir", NamedTextColor.AQUA, "/friend join " + f.getUsername(),
                                "Ir a " + where + " con " + f.getUsername())));
            }
            for (UUID id : offline) {
                me.sendMessage(Component.text("○ " + nameOf(id), NamedTextColor.DARK_GRAY));
            }
        }

        // ---------------- requests ----------------
        private void showRequests(Player me) {
            List<UUID> in;
            List<UUID> out;
            synchronized (Friends.this) {
                in = incoming(me.getUniqueId());
                out = outgoing(me.getUniqueId());
            }
            if (in.isEmpty() && out.isEmpty()) {
                say(me, "No tienes solicitudes pendientes.", NamedTextColor.YELLOW);
                return;
            }
            if (!in.isEmpty()) {
                me.sendMessage(Component.text("--- Solicitudes recibidas ---", NamedTextColor.GOLD, TextDecoration.BOLD));
                for (UUID id : in) {
                    String n = nameOf(id);
                    me.sendMessage(Component.text(n + " ", NamedTextColor.YELLOW)
                            .append(button("Aceptar", NamedTextColor.GREEN, "/friend accept " + n, "Aceptar"))
                            .append(Component.text(" "))
                            .append(button("Rechazar", NamedTextColor.RED, "/friend deny " + n, "Rechazar")));
                }
            }
            if (!out.isEmpty()) {
                me.sendMessage(Component.text("--- Solicitudes enviadas ---", NamedTextColor.GOLD, TextDecoration.BOLD));
                for (UUID id : out) {
                    me.sendMessage(Component.text(nameOf(id) + " (esperando respuesta)", NamedTextColor.GRAY));
                }
            }
        }

        // ---------------- join (lo usan los botones del chat) ----------------
        private void join(Player me, String input) {
            Ref t = resolve(input);
            boolean friend;
            synchronized (Friends.this) {
                friend = t != null && isFriend(me.getUniqueId(), t.id());
            }
            if (!friend) {
                say(me, "Ese jugador no es tu amigo.", NamedTextColor.RED);
                return;
            }
            Optional<Player> target = server.getPlayer(t.id());
            Optional<ServerConnection> conn = target.flatMap(Player::getCurrentServer);
            if (conn.isEmpty()) {
                say(me, t.name() + " no está conectado.", NamedTextColor.RED);
                return;
            }
            RegisteredServer rs = conn.get().getServer();
            String name = rs.getServerInfo().getName();
            boolean already = me.getCurrentServer()
                    .map(c -> c.getServerInfo().getName().equalsIgnoreCase(name)).orElse(false);
            if (already) {
                say(me, "Ya estás en " + name + ".", NamedTextColor.YELLOW);
                return;
            }
            say(me, "Conectando a " + name + " con " + t.name() + "...", NamedTextColor.GREEN);
            me.createConnectionRequest(rs).connect().thenAccept(res -> {
                if (!res.isSuccessful()) {
                    say(me, "No se pudo conectar a " + name + ".", NamedTextColor.RED);
                }
            });
        }
    }
}
