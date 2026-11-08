package fr.eternom.eterVelocityModeration;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.eternom.eterVelocityLib.EterVelocityLib;
import fr.eternom.eterVelocityLib.core.Config;
import fr.eternom.eterVelocityLib.helper.Messages;
import fr.eternom.eterVelocityModeration.module.sanction.Guard;
import fr.eternom.eterVelocityModeration.module.sanction.SanctionReader;
import fr.eternom.eterVelocityModeration.module.vpn.VpnCheck;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * EterVelocityModeration : la moitié proxy de la modération (EterModeration côté Paper donne les sanctions). Refuse
 * les bannis à l'entrée du réseau, déconnecte tout de suite un joueur banni ou expulsé, et (facultatif) repère les VPN.
 */
@Plugin(id = "etervelocitymoderation", name = "EterVelocityModeration", version = "2.1.0", authors = {"NadTum"},
        description = "Modération côté proxy : bannis refusés, expulsions, VPN",
        dependencies = {@Dependency(id = "etervelocitylib")})
public final class EterVelocityModeration {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

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
            // Sans le détail : une erreur YAML recopie la ligne fautive, qui peut être une clé
            logger.error("config.yml ou lang/ illisible (vérifie la syntaxe YAML), EterVelocityModeration désactivé");
            return;
        }
        proxy.getChannelRegistrar().register(Guard.CHANNEL);
        Guard guard = new Guard(this, proxy, logger, messages, new SanctionReader());
        proxy.getEventManager().register(this, guard);
        guard.start();
        if (config.getBoolean("vpn.enabled", false)) {
            proxy.getEventManager().register(this, new VpnCheck(proxy, logger, messages, config.getString("vpn.api-key", ""),
                    config.getString("vpn.action", "alert")));
        }
    }
}
