package fr.eternom.eterVelocityModeration.module.sanction;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import fr.eternom.eterVelocityLib.helper.Messages;
import fr.eternom.eterVelocityModeration.module.sanction.SanctionReader.Active;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Le gardien de l'entrée : un banni est refusé à la connexion, et déconnecté tout de suite quand un ban tombe en cours
 * de partie. Les sanctions sont lues en base à la connexion, puis relues quand un serveur Paper le demande (canal
 * eter:moderation : un serveur seulement, jamais un client). Base injoignable à la connexion : le joueur entre (et
 * c'est écrit dans la console) plutôt que de bloquer tout le réseau.
 */
public class Guard {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("eter:moderation");
    private static final MinecraftChannelIdentifier REGISTER = MinecraftChannelIdentifier.create("minecraft", "register");
    private static final String REFRESH = "refresh";

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Messages messages;
    private final SanctionReader reader;

    public Guard(Object plugin, ProxyServer proxy, Logger logger, Messages messages, SanctionReader reader) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.messages = messages;
        this.reader = reader;
    }

    @Subscribe(priority = 100)
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        return EventTask.async(() -> read(player.getUniqueId()).ifPresent(ban ->
                event.setResult(ResultedEvent.ComponentResult.denied(banScreen(player, ban)))));
    }

    /** Paper n'envoie que sur un canal déclaré par l'autre bout : on le déclare au serveur à chaque connexion d'un joueur. */
    @Subscribe
    public void onConnected(ServerPostConnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(REGISTER, CHANNEL.getId().getBytes(StandardCharsets.UTF_8)));
    }

    /** « Relis ses sanctions », d'un serveur Paper. Jamais transmis au client ; venant d'un client : ignoré. */
    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            if (REFRESH.equals(in.readUTF())) {
                refresh(UUID.fromString(in.readUTF()));
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Message de modération illisible venant de {}", ((ServerConnection) event.getSource()).getServerInfo().getName());
        }
    }

    /** Relit ses sanctions (tâche de fond) : banni = déconnecté. */
    private void refresh(UUID uuid) {
        proxy.getScheduler().buildTask(plugin, () -> proxy.getPlayer(uuid).ifPresent(player ->
                read(uuid).ifPresent(ban -> player.disconnect(banScreen(player, ban))))).schedule();
    }

    /** Son ban en cours ; vide s'il n'en a pas, ou si la base est injoignable (le joueur passe, c'est écrit). */
    private Optional<Active> read(UUID uuid) {
        try {
            return reader.ban(uuid);
        } catch (Exception e) {
            // Le message seul : une trace pourrait recopier les accès à la base
            logger.error("Sanctions de {} illisibles (base injoignable ?) : {}", uuid, e.getMessage());
            return Optional.empty();
        }
    }

    private Component banScreen(Player player, Active ban) {
        String reason = ban.reason() == null || ban.reason().isBlank() || ban.reason().equals("null") ? ban.motive() : ban.reason();
        return messages.get(player, ban.isPermanent() ? "ban.screen-permanent" : "ban.screen", TagResolver.resolver(
                Placeholder.unparsed("reason", reason), time(player, ban)));
    }

    /** <time> : ce qu'il reste à purger. */
    private TagResolver time(Player player, Active sanction) {
        if (sanction.isPermanent()) {
            return Placeholder.component("time", messages.get(player, "time.permanent", TagResolver.empty()));
        }
        long seconds = Math.max(1, (sanction.expiresAt() - System.currentTimeMillis()) / 1000);
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3600;
        long minutes = seconds % 3600 / 60;
        String key = days > 0 ? "time.days-hours" : hours > 0 ? "time.hours-minutes" : minutes > 0 ? "time.minutes-seconds" : "time.seconds";
        return Placeholder.component("time", messages.get(player, key, TagResolver.resolver(
                Placeholder.unparsed("days", String.valueOf(days)), Placeholder.unparsed("hours", String.valueOf(hours)),
                Placeholder.unparsed("minutes", String.valueOf(minutes)), Placeholder.unparsed("seconds", String.valueOf(seconds % 60)))));
    }
}
