# Fork de Maxime

Ce fork suit les releases officielles de [Wuki60](https://github.com/Wuki60/MrCuisineVibeConnect)
et y ajoute des PR upstream encore ouvertes. Les recettes personnelles restent dans
[monsieur-cuisine-recipes](https://github.com/maximehuran/monsieur-cuisine-recipes).
Releases : <https://github.com/maximehuran/MrCuisineVibeConnect/releases>.

## Contenu

`main` = dernier tag officiel Wuki60, plus les merges des PR suivantes (commits d'origine, pour
qu'un merge upstream ultérieur soit reconnu par Git), plus un seul commit propre au fork.

| PR upstream | Contenu |
|---|---|
| [nº 16](https://github.com/Wuki60/MrCuisineVibeConnect/pull/16) | Sources de recettes JSON configurables |
| [nº 2](https://github.com/Wuki60/MrCuisineVibeConnect/pull/2) | Réglages → Sécurité → Inverser le capteur du bol (désactivé par défaut) |
| [nº 4](https://github.com/Wuki60/MrCuisineVibeConnect/pull/4) | Mise en veille de l'écran (10 min par défaut) et veilleuse de la molette |
| [nº 12](https://github.com/Wuki60/MrCuisineVibeConnect/pull/12) | Racines HTTPS récentes pour Android 6 (téléchargement des mises à jour) |

Le commit du fork ne touche que :

- `core/.../update/Releases.kt` : les mises à jour viennent de ce dépôt ;
- `app/build.gradle.kts` : numéro de version fourni par la CI ;
- `.github/workflows/fork-release.yml`, `tools/fork/`, `tools/test_fork_release.py`,
  `core/src/test/.../ForkUpdateTest.kt` et ce document (fichiers propres au fork).

Le `release.yml` d'upstream reste intact, mais il est désactivé sur ce fork
(`gh workflow disable release.yml`).

## Publication

L'action **Fork releases** tourne à la minute 17 de chaque heure, à chaque push sur `main` et sur
demande. Elle récupère le tag de la dernière release officielle publiée (jamais `main` d'upstream),
le fusionne localement et lance les tests et la compilation. Elle vérifie ensuite l'APK (paquet,
version, signature, adresse de mise à jour) puis pousse la fusion et publie `vX.Y.Z.N`. En cas de
conflit ou d'échec, rien n'est publié et `main` ne change pas.

Les mises à jour restent manuelles sur le robot : **Réglages → Mises à jour**.

## Mettre à jour à la main

```sh
git remote add upstream https://github.com/Wuki60/MrCuisineVibeConnect   # une fois
git fetch upstream --tags
git merge vX.Y.Z                       # nouvelle release officielle
git fetch upstream pull/4/head && git merge FETCH_HEAD   # nouvelle version d'une PR
git push
```

Quand une PR est fusionnée upstream, il n'y a rien à faire : le merge de la release suivante
la reconnaît. Pour retirer une PR, `git revert -m 1 <merge>`.

## Organisation des réglages

À gauche : **Sécurité**, **Mises à jour**, puis **Machine** (contenu inchangé).
À droite : **Recettes**, **Mise en veille**, **Date, heure et langue**, puis **Outils**.

**Recettes → Recettes Lidl** ouvre une page regroupant la synchronisation, l’import et
l’ouverture de l’application d’origine, ainsi que la synchronisation nocturne.
**Recettes → Sources de recettes** ouvre la gestion des catalogues personnels.
Le bloc **Date, heure et langue** regroupe les anciens réglages de fuseau, d’horloge et de langue.

## Wi-Fi pendant la veille et synchronisations

**Réglages → Mise en veille → Couper le Wi-Fi pendant la veille** est activé par défaut.
Toque réactive au réveil le Wi-Fi qu’il a lui-même coupé ; un Wi-Fi désactivé manuellement
reste désactivé. Les transferts déjà lancés (recettes, heure ou APK) gardent la connexion
jusqu’à leur fin, même si l’écran passe en veille. La barre supérieure indique le Wi-Fi
connecté, en connexion ou coupé. La connexion au réseau ne garantit pas l’accès à Internet.
Toucher cet indicateur ouvre les paramètres Android.

Vers **3 h 30** (fenêtre de cinq minutes), une tâche commune vérifie les sources JSON
personnelles activées, puis Lidl si sa synchronisation automatique est activée. Elle attend
la connexion jusqu’à 45 secondes, garde l’écran en veille et recoupe le Wi-Fi à la fin,
y compris en cas d’échec. Si la personne réveille l’écran entre-temps, le Wi-Fi reste actif.
Deux nouvelles tentatives sont possibles, espacées de quinze minutes. La fenêtre de travail
est limitée à trente minutes ; un transfert HTTP en cours peut mettre un peu plus de temps
à rendre la main. Aucun programme de cuisson n’est lancé ni arrêté par cette tâche.

Une recette guidée ouverte, une machine occupée ou une base indisponible reportent la tâche.
Au réveil, une vérification chaque minute rattrape les vérifications quotidiennes dues,
avec un délai de quinze minutes entre tentatives en cas d’échec. Le robot doit rester alimenté.
Chaque source JSON se vérifie aussi au lancement de Toque. La dernière date réussie est
mise à jour après une réponse HTTP 200 **ou 304 (catalogue inchangé)** ; les erreurs ne la
font pas avancer. La page des sources montre aussi la dernière tentative et les motifs d’attente.
Les synchronisations se basent sur le jour local pour éviter de sauter une nuit si l’alarme
se déclenche quelques minutes plus tôt que la veille.
