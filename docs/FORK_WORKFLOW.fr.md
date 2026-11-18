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
