package fr.eternom.eterVelocityModeration;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.eternom.eterVelocityLib.EterVelocityLib;
import fr.eternom.eterVelocityLib.core.Config;
import fr.eternom.eterVelocityLib.helper.Messages;
import fr.eternom.eterVelocityLib.orchestrator.PoolCommand;
import fr.eternom.eterVelocityLib.orchestrator.ServerPool;
import fr.eternom.eterVelocityModeration.module.prison.Prisons;
import fr.eternom.eterVelocityModeration.module.sanction.Guard;
import fr.eternom.eterVelocityModeration.module.sanction.SanctionReader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * EterVelocityModeration : la moitié proxy de la modération (EterModeration côté Paper donne les sanctions). Refuse
 * les bannis à l'entrée et garde les prisonniers sur les serveurs prison. Les prisons sont les serveurs de
 * velocity.toml dont le nom commence par prison.server-prefix, et ceux créés sur Pterodactyl par l'orchestrateur
 * d'EterVelocityLib (famille « eterprison », facultatif) : au moins un toujours prêt, régénéré une fois vide au bout
 * de max-lifetime-hours.
 */
@Plugin(id = "etervelocitymoderation", name = "EterVelocityModeration", version = "1.0.0", authors = {"NadTum"},
        description = "Modération côté proxy : bannis refusés, prisonniers gardés en prison",
        dependencies = {@Dependency(id = "etervelocitylib")})
public final class EterVelocityModeration {

    public static final String PERMISSION = "etervelocitymoderation.admin";
    private static final Duration PING_INTERVAL = Duration.ofSeconds(5);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private ServerPool orchestrator;

    @Inject
    public EterVelocityModeration(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        Config config;
        Messages messages;
        try {
            config = new Config(EterVelocityModeration.class, dataDirectory);
            messages = EterVelocityLib.get().messages(EterVelocityModeration.class, dataDirectory, logger);
        } catch (IOException | RuntimeException e) {
            // Sans le détail : une erreur YAML recopie la ligne fautive, qui peut être une clé du panel
            logger.error("config.yml ou lang/ illisible (vérifie la syntaxe YAML), EterVelocityModeration désactivé");
            return;
        }
        Prisons[] prisons = new Prisons[1];
        if (config.getBoolean("orchestrator.enabled", false)) {
            // Un prisonnier d'une prison qu'on supprime va dans une autre prison
            orchestrator = new ServerPool(this, proxy, logger, config, dataDirectory, "eterprison",
                    List.of("eter_servers"), except -> prisons[0].best(except));
            CommandManager commands = proxy.getCommandManager();
            commands.register(commands.metaBuilder("eterprisonpool").plugin(this).build(),
                    new PoolCommand(orchestrator, messages, "eterprisonpool", PERMISSION));
        }
        prisons[0] = new Prisons(proxy, config.getString("prison.server-prefix", "prison"), orchestrator);
        proxy.getScheduler().buildTask(this, prisons[0]::ping).repeat(PING_INTERVAL).schedule();
        proxy.getChannelRegistrar().register(Guard.CHANNEL);
        Guard guard = new Guard(this, proxy, logger, messages, prisons[0], new SanctionReader(),
                config.getString("release-command", "lobby"));
        proxy.getEventManager().register(this, guard);
        guard.start();
        if (orchestrator != null) {
            orchestrator.start(dataDirectory.resolve("libs"));
        }
        if (prisons[0].names().isEmpty() && orchestrator == null) {
            logger.warn("Aucune prison : aucun serveur dont le nom commence par « {} », et l'orchestrateur est désactivé",
                    config.getString("prison.server-prefix", "prison"));
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (orchestrator != null) {
            orchestrator.stop();
        }
    }
}
