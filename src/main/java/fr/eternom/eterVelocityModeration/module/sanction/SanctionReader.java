package fr.eternom.eterVelocityModeration.module.sanction;

import fr.eternom.eterVelocityLib.EterVelocityLib;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lit les sanctions qui concernent le proxy dans etermod_sanctions (écrite par EterModeration, côté Paper) : le ban en
 * cours, et les bans et expulsions récents. La base est la seule source de vérité, le proxy ne fait que la lire.
 * Bloquant : depuis une tâche de fond. Une ancienne prison (JAIL) compte comme un ban (EterModeration 2.0.0 les
 * convertit au démarrage ; ceci couvre le proxy mis à jour avant les serveurs Paper).
 */
public class SanctionReader {

    /** Une sanction : expiresAt 0 = définitive (sans objet pour une expulsion). */
    public record Active(long id, UUID player, String type, String motive, String reason, long expiresAt) {

        public boolean isPermanent() {
            return expiresAt == 0;
        }

        public boolean isKick() {
            return "KICK".equals(type);
        }

        public boolean isActiveBan(long now) {
            return !isKick() && (expiresAt == 0 || expiresAt > now);
        }
    }

    private static final String TABLE = "etermod_sanctions";

    /** Son ban en cours (le plus long, un définitif l'emporte), s'il en a un. */
    public Optional<Active> ban(UUID player) throws IOException, SQLException {
        long now = System.currentTimeMillis();
        Optional<Active> ban = Optional.empty();
        for (Map<String, Object> row : EterVelocityLib.get().database().query("SELECT * FROM " + TABLE
                + " WHERE uuid = ? AND lifted_at = 0 AND type IN ('BAN', 'JAIL') AND (expires_at = 0 OR expires_at > ?)"
                + " ORDER BY created_at DESC", player.toString(), now)) {
            ban = longest(ban, toActive(row));
        }
        return ban;
    }

    /** Les bans et expulsions donnés depuis since (pas encore levés), du plus ancien au plus récent. */
    public List<Active> since(long since) throws IOException, SQLException {
        List<Active> list = new ArrayList<>();
        for (Map<String, Object> row : EterVelocityLib.get().database().query("SELECT * FROM " + TABLE
                + " WHERE created_at > ? AND lifted_at = 0 AND type IN ('BAN', 'JAIL', 'KICK') ORDER BY created_at", since)) {
            list.add(toActive(row));
        }
        return list;
    }

    private static Active toActive(Map<String, Object> row) {
        String type = String.valueOf(row.get("type"));
        return new Active(((Number) row.get("id")).longValue(), UUID.fromString(String.valueOf(row.get("uuid"))),
                "JAIL".equals(type) ? "BAN" : type, String.valueOf(row.get("motive")), String.valueOf(row.get("reason")),
                ((Number) row.get("expires_at")).longValue());
    }

    /** La plus longue des deux (une définitive l'emporte). */
    private static Optional<Active> longest(Optional<Active> current, Active other) {
        if (current.isEmpty() || current.get().isPermanent()) {
            return current.isEmpty() ? Optional.of(other) : current;
        }
        return other.isPermanent() || other.expiresAt() > current.get().expiresAt() ? Optional.of(other) : current;
    }
}
