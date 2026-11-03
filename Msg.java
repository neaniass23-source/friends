package dev.amigos;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

public final class Msg {
    private Msg() {}

    public static Component prefix() {
        return Component.text("[Amigos] ", NamedTextColor.GOLD, TextDecoration.BOLD);
    }

    public static Component info(String text) {
        return prefix().append(Component.text(text, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false));
    }

    public static Component ok(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false));
    }

    public static Component error(String text) {
        return prefix().append(Component.text(text, NamedTextColor.RED).decoration(TextDecoration.BOLD, false));
    }

    /** Boton clicable que ejecuta un comando. */
    public static Component button(String label, NamedTextColor color, String command, String hover) {
        return Component.text("[" + label + "]", color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    public static Sound sound(String key, float pitch) {
        try {
            return Sound.sound(Key.key(key), Sound.Source.MASTER, 1f, pitch);
        } catch (Exception e) {
            return Sound.sound(Key.key("entity.experience_orb.pickup"), Sound.Source.MASTER, 1f, pitch);
        }
    }
}
