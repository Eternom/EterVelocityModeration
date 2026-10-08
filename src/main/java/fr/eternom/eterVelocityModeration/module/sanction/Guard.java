package fr.eternom.eterVelocityModeration.module.sanction;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.eternom.eterVelocityLib.helper.Messages;
import fr.eternom.eterVelocityModeration.module.prison.Prisons;
import fr.eternom.eterVelocityModeration.module.sanction.SanctionReader.Active;
import fr.eternom.eterVelocityModeration.module.sanction.SanctionReader.Status;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Le gardien du flux : un banni est refusé à l'entrée ; un prisonnier n'est que sur une prison (arrivée, changement de
 * serveur, expulsion) ; qui n'est pas prisonnier n'entre pas en prison sans eter.mod.prison.visit. Les sanctions sont
 * lues en base à la connexion, puis relues quand un serveur Paper le demande (canal eter:moderation : un serveur
 * seulement, jamais un client) et à la fin d'une peine. Base injoignable à la connexion : le joueur entre (et c'est
 * écrit dans la console) plutôt que de bloquer tout le réseau.
 */
public class Guard {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("eter:moderation");
    public static final String VISIT_PERMISSION = "eter.mod.prison.visit";
    private static final String REFRESH = "refresh";
    /** Avant les autres plugins (EterVelocityLobby choisit le lobby avec la priorité 0) : on passe après eux. */
    private static final short LAST = -100;

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Messages messages;
    private final Prisons prisons;
    private final SanctionReader reader;
    private final String releaseCommand;
    /** Prisonniers connectés et leur prison en cours. */
    private final Map<UUID, Active> jailed = new ConcurrentHashMap<>();

    public Guard(Object plugin, ProxyServer proxy, Logger logger, Messages messages, Prisons prisons, SanctionReader reader,
                 String releaseCommand) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.messages = messages;
        this.prisons = prisons;
        this.reader = reader;
        this.releaseCommand = releaseCommand;
    }

    /** Toutes les 10 s : une peine finie est relue (une autre a pu commencer), puis le joueur libéré. */
    public void start() {
        proxy.getScheduler().buildTask(plugin, () -> {
            long now = System.currentTimeMillis();
            jailed.forEach((uuid, jail) -> {
                if (!jail.isPermanent() && jail.expiresAt() <= now) {
                    refresh(uuid);
                }
            });
        }).repeat(Duration.ofSeconds(10)).schedule();
    }

    @Subscribe(priority = 100)
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        return EventTask.async(() -> {
            Optional<Status> status = read(player.getUniqueId());
            status.flatMap(Status::jail).ifPresent(jail -> jailed.put(player.getUniqueId(), jail));
            status.flatMap(Status::ban).ifPresent(ban -> event.setResult(ResultedEvent.ComponentResult.denied(banScreen(player, ban))));
        });
    }

    @Subscribe(priority = LAST)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Player player = event.getPlayer();
        Active jail = jail(player);
        if (jail != null) {
            prisons.best(null).ifPresentOrElse(event::setInitialServer,
                    () -> player.disconnect(messages.get(player, "prison.unavailable", TagResolver.empty())));
        }
    }

    @Subscribe(priority = LAST)
    public void onPreConnect(ServerPreConnectEvent event) {
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        String name = target.get().getServerInfo().getName();
        Active jail = jail(player);
        if (jail != null && !prisons.isPrison(name)) {
            boolean inPrison = player.getCurrentServer().map(current -> prisons.isPrison(current.getServerInfo().getName())).orElse(false);
            Optional<RegisteredServer> prison = inPrison ? Optional.empty() : prisons.best(null);
            if (prison.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(prison.get()));
            } else {
                event.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
            messages.send(player, "prison.blocked", time(player, jail));
        } else if (jail == null && prisons.isPrison(name) && !player.hasPermission(VISIT_PERMISSION)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            messages.send(player, "prison.no-visit", TagResolver.empty());
        }
    }

    @Subscribe(priority = LAST)
    public void onKicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        if (jail(player) == null || (event.kickedDuringServerConnect() && player.getCurrentServer().isPresent())) {
            return;
        }
        Optional<RegisteredServer> prison = prisons.best(event.getServer().getServerInfo().getName());
        event.setResult(prison.<KickedFromServerEvent.ServerKickResult>map(server -> KickedFromServerEvent.RedirectPlayer.create(server,
                        messages.get(player, "prison.moved", TagResolver.empty())))
                .orElseGet(() -> KickedFromServerEvent.DisconnectPlayer.create(messages.get(player, "prison.unavailable", TagResolver.empty()))));
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

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        jailed.remove(event.getPlayer().getUniqueId());
    }

    /** Relit ses sanctions (tâche de fond) et applique : ban = déconnecté, prison = envoyé en prison, fin = libéré. */
    private void refresh(UUID uuid) {
        proxy.getScheduler().buildTask(plugin, () -> {
            Optional<Player> online = proxy.getPlayer(uuid);
            Optional<Status> status = read(uuid);
            if (online.isEmpty() || status.isEmpty()) {
                return;
            }
            Player player = online.get();
            Optional<Active> ban = status.get().ban();
            Optional<Active> jail = status.get().jail();
            if (ban.isPresent()) {
                jailed.remove(uuid);
                player.disconnect(banScreen(player, ban.get()));
            } else if (jail.isPresent()) {
                jailed.put(uuid, jail.get());
                boolean inPrison = player.getCurrentServer().map(current -> prisons.isPrison(current.getServerInfo().getName())).orElse(false);
                if (!inPrison) {
                    prisons.best(null).ifPresentOrElse(prison -> {
                        messages.send(player, "prison.sent", time(player, jail.get()));
                        player.createConnectionRequest(prison).fireAndForget();
                    }, () -> player.disconnect(messages.get(player, "prison.unavailable", TagResolver.empty())));
                }
            } else if (jailed.remove(uuid) != null) {
                messages.send(player, "prison.released", TagResolver.empty());
                proxy.getCommandManager().executeAsync(player, releaseCommand);
            }
        }).schedule();
    }

    /** Sa prison en cours (null s'il n'est pas prisonnier, ou si elle vient de finir : la libération suit). */
    private Active jail(Player player) {
        Active jail = jailed.get(player.getUniqueId());
        return jail == null || (!jail.isPermanent() && jail.expiresAt() <= System.currentTimeMillis()) ? null : jail;
    }

    private Optional<Status> read(UUID uuid) {
        try {
            return Optional.of(reader.read(uuid));
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
