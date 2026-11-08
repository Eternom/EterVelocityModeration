# EterVelocityModeration

La moitié **proxy** de la modération : **EterModeration** (Paper, sur chaque serveur) donne les sanctions, ce plugin
fait respecter le ban là où un serveur Paper ne peut rien faire. Document développeur, à tenir à jour avec le code.

- Un **banni** est refusé à l'entrée du réseau (écran avec la raison, la durée, et où faire appel).
- Un joueur banni **en cours de partie** est déconnecté tout de suite.

Il n'y a plus de prison (supprimée en 2.0.0) : plus de serveurs prison, plus de famille `eterprison` dans
l'orchestrateur, plus de `config.yml`.

## Prérequis

- **EterVelocityLib 1.4.0+** : la base (`database()`, accès lus dans `plugins/etervelocitylib/EterLib-config.yml`),
  langues.
- **EterModeration 2.0.0+** sur les serveurs Paper : il écrit `etermod_sanctions` et prévient le proxy.

## Données

EterVelocityModeration est la moitié proxy d'EterModeration : il lit `etermod_sanctions` directement (seule exception
à la règle « chaque plugin est seul maître de ses données »), parce qu'un plugin Paper ne peut pas répondre au proxy au
moment où un joueur se connecte. Il ne l'écrit jamais.

Une ancienne prison (`JAIL`) encore en base compte comme un ban : EterModeration 2.0.0 les convertit en `BAN` au
démarrage, le proxy les lit comme des bans s'il est mis à jour avant.

## Fonctionnement (`Guard`)

- **Connexion** (`LoginEvent`, en tâche de fond) : son ban en cours est lu en base (`SanctionReader` : non levé, non
  fini ; le plus long). Base injoignable : le joueur entre (c'est écrit dans la console) plutôt que de fermer tout le
  réseau.
- **Ban en cours de partie** : le serveur Paper où est le joueur envoie `refresh` + UUID sur le canal
  `eter:moderation`. Accepté **seulement d'un serveur** (`ServerConnection`), jamais transmis au client ; le message ne
  porte que l'UUID et tout est relu en base : un joueur ne peut ni se débannir ni bannir quelqu'un.
- **Appel** : un banni ne peut pas faire `/appel` en jeu ; l'écran du ban donne le Discord (`lang/` > `ban.screen`).

## Passer de 1.x à 2.0.0

- Supprimer `plugins/etervelocitymoderation/config.yml` sur le proxy : il n'est plus lu, et il contient les clés du
  panel Pterodactyl de l'ancienne famille `eterprison`.
- Si l'orchestrateur des prisons était activé : supprimer à la main, dans le panel, les serveurs `prison...` qu'il avait
  créés, puis la table `eterprison_servers`.
- Retirer les serveurs `prison...` de `velocity.toml` s'il y en a.
