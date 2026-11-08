package fr.eternom.eterVelocityModeration.module.vpn;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.eternom.eterVelocityLib.helper.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.slf4j.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Détection des VPN et proxys à la connexion (config.yml > vpn, désactivée par défaut) : l'adresse du joueur est
 * demandée à proxycheck.io (l'adresse quitte donc le serveur : à n'activer qu'en le sachant, et en le disant dans les
 * règles). Réponse gardée en mémoire 24 h. action « alert » : le staff connecté (eter.mod.alerts) est prévenu ;
 * « deny » : la connexion est refusée. Dispense : eter.mod.bypass.vpn. Service injoignable : le joueur entre.
 */
public class VpnCheck {

    public static final String BYPASS = "eter.mod.bypass.vpn";
    private static final String ALERTS = "eter.mod.alerts";
    private static final Duration CACHE = Duration.ofHours(24);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private record Answer(boolean proxy, long at) {
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Messages messages;
    private final String apiKey;
    private final boolean deny;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final Map<String, Answer> cache = new ConcurrentHashMap<>();

    public VpnCheck(ProxyServer proxy, Logger logger, Messages messages, String apiKey, String action) {
        this.proxy = proxy;
        this.logger = logger;
        this.messages = messages;
        this.apiKey = apiKey;
        this.deny = "deny".equalsIgnoreCase(action);
    }

    @Subscribe(priority = 50)
    public EventTask onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        if (!event.getResult().isAllowed() || player.hasPermission(BYPASS)) {
            return null;
        }
        String address = player.getRemoteAddress().getAddress().getHostAddress();
        return EventTask.async(() -> {
            if (!isProxy(address)) {
                return;
            }
            if (deny) {
                event.setResult(ResultedEvent.ComponentResult.denied(messages.get(player, "vpn.denied", TagResolver.empty())));
            }
            // Jamais l'adresse dans l'alerte : seulement le pseudo
            proxy.getAllPlayers().stream().filter(staff -> staff.hasPermission(ALERTS)).forEach(staff ->
                    staff.sendMessage(messages.get(staff, deny ? "vpn.alert-denied" : "vpn.alert",
                            TagResolver.resolver(Placeholder.unparsed("player", player.getUsername())))));
        });
    }

    private boolean isProxy(String address) {
        long now = System.currentTimeMillis();
        Answer cached = cache.get(address);
        if (cached != null && now - cached.at() < CACHE.toMillis()) {
            return cached.proxy();
        }
        try {
            String url = "https://proxycheck.io/v2/" + URLEncoder.encode(address, StandardCharsets.UTF_8) + "?vpn=1"
                    + (apiKey.isBlank() ? "" : "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
            boolean isProxy = body.has(address) && "yes".equalsIgnoreCase(body.getAsJsonObject(address).get("proxy").getAsString());
            cache.put(address, new Answer(isProxy, now));
            if (cache.size() > 50_000) {
                cache.clear();
            }
            return isProxy;
        } catch (Exception e) {
            logger.warn("Vérification VPN impossible (service injoignable) : le joueur entre");
            return false;
        }
    }
}
