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
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le gardien de l'entrée : un banni est refusé à la connexion, déconnecté tout de suite quand un ban tombe en cours de
 * partie, et un joueur expulsé (KICK) est déconnecté une fois. Trois chemins, du plus rapide au plus sûr :
 * - le serveur Paper du joueur demande « relis ses sanctions » (canal eter:moderation : un serveur seulement) ;
 * - toutes les 5 s, les bans et expulsions donnés depuis la dernière lecture (même si le joueur est sur un serveur sans
 *   EterModeration) ;
 * - à la connexion, son ban en cours.
 * Base injoignable à la connexion : un ban déjà lu reste appliqué (mémoire) ; sinon le joueur entre (et c'est écrit
 * dans la console) plutôt que de bloquer tout le réseau.
 */
public class Guard {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("eter:moderation");
    private static final MinecraftChannelIdentifier REGISTER = MinecraftChannelIdentifier.create("minecraft", "register");
    private static final String REFRESH = "refresh";
    private static final Duration POLL = Duration.ofSeconds(5);
    /** Une expulsion plus vieille que ça n'est plus appliquée (le joueur a pu revenir). */
    private static final long KICK_WINDOW_MILLIS = 30_000;

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Messages messages;
    private final SanctionReader reader;
    /** Les bans lus récemment : appliqués même si la base ne répond plus. */
    private final Map<UUID, Active> knownBans = new ConcurrentHashMap<>();
    /** Expulsions déjà appliquées (une seule fois chacune). */
    private final Set<Long> handledKicks = ConcurrentHashMap.newKeySet();
    private volatile long lastPoll = System.currentTimeMillis();

    public Guard(Object plugin, ProxyServer proxy, Logger logger, Messages messages, SanctionReader reader) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.messages = messages;
        this.reader = reader;
    }

    public void start() {
        proxy.getScheduler().buildTask(plugin, this::poll).delay(POLL).repeat(POLL).schedule();
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
                UUID.fromString(in.readUTF());
                proxy.getScheduler().buildTask(plugin, this::poll).schedule();
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.warn("Message de modération illisible venant de {}", ((ServerConnection) event.getSource()).getServerInfo().getName());
        }
    }

    /** Les bans et expulsions donnés depuis la dernière lecture : appliqués aux joueurs connectés. */
    private synchronized void poll() {
        long now = System.currentTimeMillis();
        // Un peu de recouvrement : une sanction écrite pendant la lecture précédente n'est pas perdue
        long since = lastPoll - 2_000;
        try {
            for (Active sanction : reader.since(since)) {
                if (sanction.isKick()) {
                    if (now - sanction.expiresAt() > KICK_WINDOW_MILLIS || !handledKicks.add(sanction.id())) {
                        continue;
                    }
                    proxy.getPlayer(sanction.player()).ifPresent(player -> player.disconnect(kickScreen(player, sanction)));
                } else if (sanction.isActiveBan(now)) {
                    knownBans.merge(sanction.player(), sanction, (old, other) -> old.isPermanent() ? old : other);
                    proxy.getPlayer(sanction.player()).ifPresent(player -> player.disconnect(banScreen(player, sanction)));
                }
            }
            lastPoll = now;
        } catch (Exception e) {
            // Le message seul : une trace pourrait recopier les accès à la base
            logger.error("Nouvelles sanctions illisibles (base injoignable ?) : {}", e.getMessage());
        }
        if (handledKicks.size() > 10_000) {
            handledKicks.clear();
        }
    }

    /** Son ban en cours ; vide s'il n'en a pas. Base injoignable : le dernier ban lu, s'il court encore. */
    private Optional<Active> read(UUID uuid) {
        try {
            Optional<Active> ban = reader.ban(uuid);
            ban.ifPresentOrElse(found -> knownBans.put(uuid, found), () -> knownBans.remove(uuid));
            return ban;
        } catch (Exception e) {
            logger.error("Sanctions de {} illisibles (base injoignable ?) : {}", uuid, e.getMessage());
            return Optional.ofNullable(knownBans.get(uuid)).filter(ban -> ban.isActiveBan(System.currentTimeMillis()));
        }
    }

    private Component banScreen(Player player, Active ban) {
        return messages.get(player, ban.isPermanent() ? "ban.screen-permanent" : "ban.screen", TagResolver.resolver(
                Placeholder.unparsed("reason", reason(ban)), Placeholder.unparsed("id", String.valueOf(ban.id())), time(player, ban)));
    }

    private Component kickScreen(Player player, Active kick) {
        return messages.get(player, "kick.screen", TagResolver.resolver(Placeholder.unparsed("reason", reason(kick)),
                Placeholder.unparsed("id", String.valueOf(kick.id()))));
    }

    private static String reason(Active sanction) {
        return sanction.reason() == null || sanction.reason().isBlank() || sanction.reason().equals("null")
                ? sanction.motive() : sanction.reason();
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
