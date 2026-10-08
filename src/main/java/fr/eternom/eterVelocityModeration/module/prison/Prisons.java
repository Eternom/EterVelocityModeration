package fr.eternom.eterVelocityModeration.module.prison;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.eternom.eterVelocityLib.orchestrator.ServerPool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Les serveurs prison : ceux de velocity.toml dont le nom commence par prison.server-prefix, plus ceux créés par
 * l'orchestrateur (famille « eterprison », s'il est activé). Interrogés toutes les 5 s ; la meilleure prison est celle
 * qui répond et a le moins de joueurs. EterChat reconnaît les mêmes serveurs à leur nom (chat de la prison à part).
 */
public class Prisons {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(3);

    private final ProxyServer proxy;
    private final String prefix;
    private final ServerPool orchestrator; // null s'il est désactivé
    private final Set<String> reachable = ConcurrentHashMap.newKeySet();

    public Prisons(ProxyServer proxy, String prefix, ServerPool orchestrator) {
        this.proxy = proxy;
        this.prefix = prefix.toLowerCase(Locale.ROOT);
        this.orchestrator = orchestrator;
    }

    /** Un serveur prison (même en création ou en vidange). */
    public boolean isPrison(String server) {
        return server.toLowerCase(Locale.ROOT).startsWith(prefix) || (orchestrator != null && orchestrator.manages(server));
    }

    /** Les prisons qui reçoivent des joueurs. */
    public List<String> names() {
        List<String> names = new ArrayList<>();
        for (RegisteredServer server : proxy.getAllServers()) {
            String name = server.getServerInfo().getName();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix) && (orchestrator == null || !orchestrator.manages(name))) {
                names.add(name);
            }
        }
        if (orchestrator != null) {
            orchestrator.activeNames().stream().filter(name -> !names.contains(name)).forEach(names::add);
        }
        return names;
    }

    /** Prison qui répond, la moins remplie, autre que except (peut être null). */
    public Optional<RegisteredServer> best(String except) {
        return names().stream()
                .filter(name -> !name.equals(except) && reachable.contains(name))
                .map(proxy::getServer)
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(server -> server.getPlayersConnected().size()));
    }

    /** Toutes les 5 s (tâche du proxy) : quelles prisons répondent. */
    public void ping() {
        for (String name : names()) {
            proxy.getServer(name).ifPresentOrElse(server -> server.ping()
                            .orTimeout(PING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    reachable.add(name);
                                } else {
                                    reachable.remove(name);
                                }
                            }),
                    () -> reachable.remove(name));
        }
    }
}
