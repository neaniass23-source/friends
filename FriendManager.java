package dev.amigos;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class FriendManager {

    static class Data {
        Map<String, String> names = new HashMap<>();             // uuid -> nombre
        Map<String, Set<String>> friends = new HashMap<>();      // uuid -> amigos
        Map<String, Set<String>> requests = new HashMap<>();     // destino -> remitentes
    }

    private final Path file;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private Data data = new Data();

    public FriendManager(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public synchronized void load() {
        if (!Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file)) {
            Data d = gson.fromJson(r, Data.class);
            if (d != null) data = d;
        } catch (Exception e) {
            logger.error("Error leyendo friends.json", e);
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                gson.toJson(data, w);
            }
        } catch (IOException e) {
            logger.error("Error guardando friends.json", e);
        }
    }

    public synchronized void updateName(UUID id, String name) {
        String old = data.names.put(id.toString(), name);
        if (!name.equals(old)) save();
    }

    public synchronized String name(UUID id) {
        return data.names.getOrDefault(id.toString(), id.toString().substring(0, 8));
    }

    public synchronized UUID findByName(String name) {
        for (Map.Entry<String, String> e : data.names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return UUID.fromString(e.getKey());
        }
        return null;
    }

    public synchronized boolean areFriends(UUID a, UUID b) {
        return data.friends.getOrDefault(a.toString(), Set.of()).contains(b.toString());
    }

    public synchronized Set<UUID> friendsOf(UUID id) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String s : data.friends.getOrDefault(id.toString(), Set.of())) out.add(UUID.fromString(s));
        return out;
    }

    public synchronized void addFriends(UUID a, UUID b) {
        data.friends.computeIfAbsent(a.toString(), k -> new LinkedHashSet<>()).add(b.toString());
        data.friends.computeIfAbsent(b.toString(), k -> new LinkedHashSet<>()).add(a.toString());
        save();
    }

    public synchronized void removeFriends(UUID a, UUID b) {
        Set<String> sa = data.friends.get(a.toString());
        Set<String> sb = data.friends.get(b.toString());
        if (sa != null) sa.remove(b.toString());
        if (sb != null) sb.remove(a.toString());
        save();
    }

    public synchronized boolean hasRequest(UUID from, UUID to) {
        return data.requests.getOrDefault(to.toString(), Set.of()).contains(from.toString());
    }

    public synchronized void addRequest(UUID from, UUID to) {
        data.requests.computeIfAbsent(to.toString(), k -> new LinkedHashSet<>()).add(from.toString());
        save();
    }

    public synchronized void removeRequest(UUID from, UUID to) {
        Set<String> s = data.requests.get(to.toString());
        if (s != null) s.remove(from.toString());
        save();
    }

    public synchronized Set<UUID> requestsTo(UUID to) {
        Set<UUID> out = new LinkedHashSet<>();
        for (String s : data.requests.getOrDefault(to.toString(), Set.of())) out.add(UUID.fromString(s));
        return out;
    }
}
