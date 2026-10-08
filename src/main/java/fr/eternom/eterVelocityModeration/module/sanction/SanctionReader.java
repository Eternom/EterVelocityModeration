package fr.eternom.eterVelocityModeration.module.sanction;

import fr.eternom.eterVelocityLib.EterVelocityLib;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lit les sanctions en cours d'un joueur dans etermod_sanctions (écrite par EterModeration, côté Paper) : la base est
 * la seule source de vérité, le proxy ne fait que la lire. Bloquant : depuis une tâche de fond.
 */
public class SanctionReader {

    /** Une sanction en cours : expiresAt 0 = définitive. */
    public record Active(String motive, String reason, long expiresAt) {

        public boolean isPermanent() {
            return expiresAt == 0;
        }
    }

    /** La prison et le ban en cours (la plus longue de chaque), s'il y en a. */
    public record Status(Optional<Active> ban, Optional<Active> jail) {

        public static final Status FREE = new Status(Optional.empty(), Optional.empty());

        public boolean isFree() {
            return ban.isEmpty() && jail.isEmpty();
        }
    }

    private static final String TABLE = "etermod_sanctions";

    public Status read(UUID player) throws IOException, SQLException {
        long now = System.currentTimeMillis();
        Optional<Active> ban = Optional.empty();
        Optional<Active> jail = Optional.empty();
        for (Map<String, Object> row : EterVelocityLib.get().database().query("SELECT type, motive, reason, expires_at FROM " + TABLE
                + " WHERE uuid = ? AND lifted_at = 0 AND type IN ('BAN', 'JAIL') AND (expires_at = 0 OR expires_at > ?)"
                + " ORDER BY created_at DESC", player.toString(), now)) {
            Active active = new Active(String.valueOf(row.get("motive")), String.valueOf(row.get("reason")),
                    ((Number) row.get("expires_at")).longValue());
            if ("BAN".equals(row.get("type"))) {
                ban = longest(ban, active);
            } else {
                jail = longest(jail, active);
            }
        }
        return new Status(ban, jail);
    }

    /** La plus longue des deux (une définitive l'emporte). */
    private static Optional<Active> longest(Optional<Active> current, Active other) {
        if (current.isEmpty() || current.get().isPermanent()) {
            return current.isEmpty() ? Optional.of(other) : current;
        }
        return other.isPermanent() || other.expiresAt() > current.get().expiresAt() ? Optional.of(other) : current;
    }
}
