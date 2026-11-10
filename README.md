# EterVelocityModeration

La moitié **proxy** de la modération : **EterModeration** (Paper, sur chaque serveur) donne les sanctions, ce plugin
fait respecter le ban et l'expulsion là où un serveur Paper ne peut rien faire. Document développeur, à tenir à jour
avec le code.

- Un **banni** est refusé à l'entrée du réseau (écran avec la raison, la durée, le numéro de la sanction et où faire
  appel).
- Un joueur banni ou **expulsé** en cours de partie est déconnecté tout de suite.
- Facultatif : les **VPN** et proxys sont repérés à la connexion (alerte au staff, ou refus).

Il n'y a plus de prison (supprimée en 2.0.0).

## Prérequis

- **EterVelocityLib 1.4.0+** : la base (`database()`, accès lus dans `plugins/etervelocitylib/EterLib-config.yml`),
  langues, config.
- **EterModeration 2.1.0+** sur les serveurs Paper : il écrit `etermod_sanctions` (index sur `uuid` et `created_at`)
  et prévient le proxy.

## Données

EterVelocityModeration est la moitié proxy d'EterModeration : il lit `etermod_sanctions` directement (seule exception
à la règle « chaque plugin est seul maître de ses données »), parce qu'un plugin Paper ne peut pas répondre au proxy au
moment où un joueur se connecte. Il ne l'écrit jamais.

Une ancienne prison (`JAIL`) encore en base compte comme un ban : EterModeration 2.0.0+ les convertit en `BAN` au
démarrage, le proxy les lit comme des bans s'il est mis à jour avant.

## Fonctionnement (`Guard`)

- **Connexion** (`LoginEvent`, en tâche de fond) : son ban en cours est lu en base (`SanctionReader` : non levé, non
  fini ; le plus long). Base injoignable : un ban déjà lu reste appliqué (mémoire) ; sinon le joueur entre (c'est écrit
  dans la console) plutôt que de fermer tout le réseau.
- **Toutes les 5 s** : les bans et expulsions donnés depuis la dernière lecture sont appliqués aux joueurs connectés,
  même sur un serveur sans EterModeration. Une expulsion n'est appliquée qu'une fois, et dans les 30 s.
- **Tout de suite** : le serveur Paper où est le joueur envoie `refresh` + UUID sur le canal `eter:moderation` ; le
  proxy relit alors les nouvelles sanctions. Accepté **seulement d'un serveur** (`ServerConnection`), jamais transmis au
  client : un joueur ne peut ni se débannir ni bannir quelqu'un.
- **Appel** : un banni ne peut pas faire `/appeal` en jeu ; l'écran du ban donne le numéro de la sanction et le Discord
  (`lang/` > `ban.screen`).

## VPN (`VpnCheck`, `config.yml > vpn`, désactivé par défaut)

L'adresse du joueur est demandée à proxycheck.io : elle **quitte donc le serveur**. À n'activer qu'en le sachant, et en
le disant dans les règles. `action: alert` (le staff connecté avec `eter.mod.alerts` est prévenu, sans l'adresse) ou
`deny` (connexion refusée). Réponses gardées 24 h en mémoire ; service injoignable : le joueur entre. Dispense :
`eter.mod.bypass.vpn`.

## Passer de 1.x à 2.x

- `plugins/etervelocitymoderation/config.yml` de la 1.x contient les clés du panel de l'ancienne famille
  `eterprison` : le supprimer (la 2.1 en recrée un, sans clé).
- Si l'orchestrateur des prisons était activé : supprimer à la main, dans le panel, les serveurs `prison...` qu'il avait
  créés, puis la table `eterprison_servers`.
- Retirer les serveurs `prison...` de `velocity.toml` s'il y en a.
