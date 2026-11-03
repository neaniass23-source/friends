package dev.amigos;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Plugin(id = "friends", name = "Friends", version = "1.0.0", authors = {"Tu nombre"})
public class FriendsPlugin {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final Properties config = new Properties();
    private FriendManager manager;

    @Inject
    public FriendsPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        loadConfig();
        manager = new FriendManager(dataDirectory.resolve("friends.json"), logger);
        manager.load();

        CommandManager cm = proxy.getCommandManager();
        CommandMeta meta = cm.metaBuilder("friend")
                .aliases("friends", "amigo", "amigos", "f")
                .plugin(this)
                .build();
        cm.register(meta, new FriendCommand(proxy, manager, this));

        proxy.getEventManager().register(this, new FriendListener(proxy, manager, this));
        logger.info("Friends cargado correctamente.");
    }

    private void loadConfig() {
        try {
            Files.createDirectories(dataDirectory);
            Path file = dataDirectory.resolve("config.properties");
            if (!Files.exists(file)) {
                Properties def = new Properties();
                def.setProperty("lobby-server", "lobby");
                def.setProperty("survival-server", "survival");
                // Servidores donde se mostrará el aviso (separados por coma)
                def.setProperty("notify-on-servers", "survival");
                // Si es false, solo avisa al entrar a la red; si es true, cada vez que pise el lobby
                def.setProperty("notify-on-every-lobby-visit", "false");
                def.setProperty("sound-join", "entity.experience_orb.pickup");
                def.setProperty("sound-request", "block.note_block.pling");
                try (OutputStream out = Files.newOutputStream(file)) {
                    def.store(out, "Configuracion de Friends");
                }
            }
            try (InputStream in = Files.newInputStream(file)) {
                config.load(in);
            }
        } catch (IOException e) {
            logger.error("No se pudo cargar la configuracion", e);
        }
    }

    public String cfg(String key) {
        return config.getProperty(key, "");
    }
}
