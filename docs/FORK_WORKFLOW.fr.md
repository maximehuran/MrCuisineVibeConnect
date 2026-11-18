# Le fork du robot de Maxime

Ce dépôt fournit l'application du robot. Les recettes restent une source séparée dans
[monsieur-cuisine-recipes](https://github.com/maximehuran/monsieur-cuisine-recipes).

## Sur le robot

Après l'installation initiale de l'APK du fork, **Réglages → Mises à jour → Rechercher une mise à jour**
consulte uniquement les releases de `maximehuran/MrCuisineVibeConnect`. L'utilisateur confirme
**Installer** ; Toque télécharge l'APK, l'installe et redémarre. Aucune recherche ni installation de
l'application n'est programmée sur le robot. Les garde-fous d'installation du projet sont conservés.

Dans **Réglages → Sources de recettes JSON → Gérer les catalogues personnels**, ajouter une source
et l'adresse suivante, puis synchroniser :

```text
https://api.github.com/repos/maximehuran/monsieur-cuisine-recipes/contents/catalog.json
```

La synchronisation automatique des recettes est indépendante des mises à jour de l'application.
Le premier essai de la fiche « Prélavage » porte sur l'import et l'affichage, sans lancer le moteur.

## Récupérer les évolutions officielles

L'action **Fork releases** s'exécute à la minute 17 de chaque heure, lors d'un ajout à `main`, ou
manuellement depuis GitHub Actions. Elle interroge la dernière release officielle publiée de
`Wuki60/MrCuisineVibeConnect` et refuse les brouillons, préversions et releases sans APK. Elle
récupère exactement le commit du tag publié, jamais la tête non publiée de `upstream/main`.

1. Préparer la fusion avec le fork dans le dossier temporaire du runner, sans pousser.
2. Vérifier que la base officielle avance et que le dépôt des mises à jour reste celui du fork.
3. Exécuter les tests du fork, de migration SQLite et du cœur de Toque ; compiler l'APK.
4. Vérifier la signature existante, le paquet `app.toque`, les numéros de version et l'adresse de
   mise à jour réellement compilée dans l'APK.
5. Pousser la fusion testée et son tag ensemble, sans forcer, puis publier l'APK avec son empreinte
   et son manifeste de provenance. L'APK devient visible dans les mises à jour après publication.

Un conflit, un échec de test ou une vérification incorrecte interrompt le travail. Aucune nouvelle
application non validée n'est proposée au robot. Une modification concurrente de `main` annule la
publication : le prochain passage repart de sa nouvelle version. Une panne de publication après
la poussée du tag peut laisser un tag ou brouillon sans release complète ; le prochain passage
réessaie avec un numéro neuf. Les numéros abandonnés ne sont jamais réutilisés.

Si rien n'a changé depuis le dernier APK publié, il n'y a ni compilation ni nouvelle release.
Le manifeste de chaque release contient la base officielle, le commit du fork, son arbre source,
sa version Android, l'empreinte de l'APK et le lien vers la compilation. Il ne certifie pas un test
physique sur le robot : les tests automatiques ne remplacent pas cette validation.

## Numérotation et contributions

Les versions du fork suivent `version-officielle.compteur`, par exemple `0.5.1.1`, `0.5.1.2`, puis
`0.5.2.3`. Le compteur est global et ne redescend jamais ; le `versionCode` Android vaut
`10000 + compteur`. Ces valeurs sont fournies à Gradle lors de la compilation, en conservant les
valeurs de base officielles dans les sources. Chaque release porte le même tag que la version APK.

`main` contient les fonctionnalités utilisées sur le robot et les réglages de ce fork. Une branche
`feat/...` destinée à une PR chez Wuki60 part de la base officielle et ne contient que sa fonctionnalité.
La PR JSON nº 16 reste indépendante du présent réglage des mises à jour. Les nouveautés sont
intégrées à `main` après validation ; lorsqu'elles arrivent dans une release officielle, on vérifie
la fusion et on retire les adaptations devenues redondantes si nécessaire. Ne jamais utiliser une
synchronisation qui réinitialise `main` sur Wuki60 : elle effacerait les fonctionnalités du fork.

Les commits humains et les fusions automatiques utilisent `maxime.huran@gmail.com`.
Les licences originales et la signature de l'application sont conservées.

## Installation initiale et retour arrière

Sauvegarder les données de Toque **avant** l'installation initiale : la base de recettes passe de
la version 1 à la version 2. Une sauvegarde des partitions boot/recovery ne remplace pas cette
sauvegarde applicative. Une réinstallation de Toque 0.5.1 seule ne peut pas relire cette nouvelle
base ; restaurer aussi les données antérieures pour revenir à cette version.

Les vérifications horaires dépendent de GitHub Actions et peuvent être retardées. GitHub peut
désactiver une action programmée après 60 jours sans activité du dépôt. Consulter l'onglet Actions
en cas d'absence prolongée de nouvelles versions ; réactiver puis lancer **Fork releases** si besoin.
Une synchronisation à la demande reste disponible via **Run workflow**. Le robot reste utilisable
avec son application installée et ses recettes en cache pendant une indisponibilité de GitHub.
