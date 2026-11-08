package fr.eternom.eterVelocityModeration;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.eternom.eterVelocityLib.EterVelocityLib;
import fr.eternom.eterVelocityLib.helper.Messages;
import fr.eternom.eterVelocityModeration.module.sanction.Guard;
import fr.eternom.eterVelocityModeration.module.sanction.SanctionReader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;

/**
 * EterVelocityModeration : la moitié proxy de la modération (EterModeration côté Paper donne les sanctions). Refuse
 * les bannis à l'entrée du réseau et déconnecte tout de suite un joueur qui vient d'être banni.
 */
@Plugin(id = "etervelocitymoderation", name = "EterVelocityModeration", version = "2.0.0", authors = {"NadTum"},
        description = "Modération côté proxy : bannis refusés à l'entrée",
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
        Messages messages;
        try {
            messages = EterVelocityLib.get().messages(EterVelocityModeration.class, dataDirectory, logger);
        } catch (IOException | RuntimeException e) {
            logger.error("lang/ illisible (vérifie la syntaxe YAML), EterVelocityModeration désactivé");
            return;
        }
        proxy.getChannelRegistrar().register(Guard.CHANNEL);
        proxy.getEventManager().register(this, new Guard(this, proxy, logger, messages, new SanctionReader()));
    }
}
