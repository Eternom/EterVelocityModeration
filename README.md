# EterVelocityModeration

La moitié **proxy** de la modération : **EterModeration** (Paper, sur chaque serveur) donne les sanctions, ce plugin
les fait respecter là où un serveur Paper ne peut rien faire. Document développeur, à tenir à jour avec le code.

- Un **banni** est refusé à l'entrée du réseau (écran avec la raison et la durée).
- Un **prisonnier** n'est que sur une **prison** : à l'arrivée, à chaque changement de serveur (`/server`, `/lobby`,
  portail...), après une expulsion. À la fin de sa peine, il est libéré et renvoyé au lobby (`release-command`).
- Qui n'est pas prisonnier n'entre pas en prison, sauf avec `eter.mod.prison.visit` (staff).

La vie en prison (travail, remise de peine) viendra dans un plugin dédié, côté Paper.

## Prérequis

- **EterVelocityLib 1.3.0+** : la base (`database()`, accès lus dans `plugins/etervelocitylib/EterLib-config.yml`),
  langues, orchestrateur.
- **EterModeration** sur les serveurs Paper : il écrit `etermod_sanctions` et prévient le proxy.
- **EterVelocityLobby** (conseillé) : `/lobby` à la libération.

## Fonctionnement (`Guard`)

- **Connexion** (`LoginEvent`, en tâche de fond) : ses bans et prisons en cours sont lus en base (`SanctionReader` :
  non levés, non finis ; la plus longue de chaque). Base injoignable : le joueur entre (c'est écrit dans la console)
  plutôt que de fermer tout le réseau.
- **Premier serveur** et **changement de serveur** : priorité basse (-100), après EterVelocityLobby, pour avoir le
  dernier mot.
- **Changement en cours de partie** : le serveur Paper où est le joueur envoie `refresh` + UUID sur le canal
  `eter:moderation`. Accepté **seulement d'un serveur** (`ServerConnection`), jamais transmis au client ; le message ne
  porte que l'UUID et tout est relu en base : un joueur ne peut ni se libérer ni sanctionner quelqu'un.
- **Fin de peine** : vérifiée toutes les 10 s ; la base est relue (une autre peine a pu commencer) avant de libérer.

## Les prisons (`Prisons`)

Une prison est un serveur dont le nom commence par `prison.server-prefix` (`prison` ; le même préfixe qu'EterChat
pour son chat de prison), déclaré dans `velocity.toml` **ou** créé par l'orchestrateur. La meilleure prison est celle
qui répond (ping toutes les 5 s) et a le moins de joueurs. Aucune prison ouverte : le prisonnier est déconnecté avec
un message (reconnexion dans quelques minutes).

### Orchestrateur (facultatif, famille `eterprison`)

Comme les lobbys et les mondes ressources (moteur d'EterVelocityLib, `ServerPool`) : au moins `minimum` prisons
prêtes, une de plus quand elles se remplissent, et au bout de `max-lifetime-hours` une prison est vidée puis
remplacée par une neuve, **une fois vide** (`drain-timeout-minutes` long : un prisonnier n'est déplacé dans une autre
prison qu'en dernier recours). Commande d'administration : `/eterprisonpool list | status | create | drain`
(`etervelocitymoderation.admin`).

Pas d'EterSync dans les plugins des prisons : l'inventaire de la prison reste en prison, le vrai attend le joueur.

**Sécurité** : le panel héberge aussi d'autres serveurs. Seuls les serveurs de `eterprison_servers`, appartenant à
`owner-user-id` et portant l'identifiant externe `eterprison:<nom>` peuvent être supprimés ; dry-run d'abord.
