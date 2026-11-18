# Deux versions pour le robot de Maxime

Ce dépôt publie **Fork Maxime** et **Référence Wuki60**. Les recettes personnelles restent dans
[monsieur-cuisine-recipes](https://github.com/maximehuran/monsieur-cuisine-recipes).

## Sur le robot

**Réglages → Version de l’application** indique la version utilisée et propose l’autre. Après
confirmation, Toque télécharge l’APK, vérifie sa provenance, son empreinte, son édition, sa signature
et son numéro, puis libère la liaison machine, installe et redémarre. Le changement est refusé pendant
un programme ou une recette guidée. Une erreur de téléchargement ne suspend pas la liaison machine.

Le sélecteur existe dans les deux versions. Les deux APK portent le même paquet `app.toque`, la même
signature et le même numéro de compilation. Le changement d’édition à numéro égal est explicitement
autorisé ; les anciennes compilations restent refusées. Une seule application fonctionne à la fois.

La recherche de mises à jour reste **manuelle**, dans **Réglages → Mises à jour**. Les deux versions
consultent ce dépôt. La référence suit uniquement les vraies releases officielles de Wuki60 ; elle
conserve une petite adaptation pour le sélecteur et la distribution. Il ne s’agit pas de l’APK original
inchangé de Wuki60 : installer cet APK original ferait disparaître le sélecteur.

## Données indépendantes

| Données | Fork | Référence |
|---|---|---|
| Recettes et favoris | `recettes.db` existant | `recettes-reference.db` |
| Préférences | `toque` existant | `toque-reference` |
| Images | `files/images` existant | `files/images-reference` |

Aucun fichier du fork n’est renommé, converti ou effacé pendant un changement. Chaque version retrouve
son propre catalogue, ses favoris et ses préférences. Les favoris ne passent pas d’une version à l’autre.
Au premier lancement de la référence, seuls les réglages de machine, de sécurité, de langue et
quelques réglages matériels sont copiés du fork. Les recettes, sources JSON, synchronisations et
reprises de cuisson ne sont pas copiées. Le catalogue de référence est initialement vide : utiliser
**Réglages → Recettes Lidl → Synchroniser** pour le remplir. Les deux schémas peuvent évoluer séparément.

Pour comparer les favoris, utiliser la même recette Lidl, ajouter un favori, ouvrir la liste, puis
redémarrer. Le code de favoris de la référence est conservé exactement depuis sa release officielle ;
seuls les noms de fichiers sont adaptés dans son dépôt de recettes.

Les sources JSON du fork restent configurables. Exemple :

```text
https://api.github.com/repos/maximehuran/monsieur-cuisine-recipes/contents/catalog.json
```

Le premier essai de « Prélavage » porte sur l’import et l’affichage. Aucune synchronisation ne lance
les lames ni la chauffe.

## Publication et suivi de Wuki60

L’action **Fork releases**, à la minute 17 de chaque heure ou sur demande, récupère exactement le tag
numérique de la dernière release officielle publiée, jamais les modifications non publiées de `main`.
Elle refuse les brouillons, préversions et releases sans APK. Elle prépare une fusion locale, conserve
les fonctionnalités du fork et prépare la référence depuis le tag officiel avec une liste contrôlée
de fichiers de distribution. Un audit vérifie que sa logique de recettes/favoris est restée inchangée.

Les deux versions passent leurs tests et leur compilation. Un émulateur Android 6 vérifie le passage
fork → référence → fork, la persistance des favoris/préférences et les connexions HTTPS GitHub. Les APK
sont ensuite vérifiés et publiés. Aucun conflit ou échec de validation ne doit diffuser une nouvelle
paire. Une modification concurrente de `main` annule la publication ; aucun push n’est forcé.

Les numéros suivent `version-officielle.compteur`, par exemple `0.5.1.3`, et le code Android vaut
`10000 + compteur`. Les numéros réservés par un échec ne sont jamais réutilisés. Sans changement de
sources, il n’y a pas de nouvelle compilation ou release.

La release normale contient uniquement l’APK du fork : les anciennes versions choisissent le premier
APK et doivent continuer à recevoir le fork. L’APK de référence est publié dans `reference-v…`, une
release de distribution marquée préversion pour rester exclue du flux « latest ». `edition-manifest.json`
relie les deux APK vérifiés ; le sélecteur le lit automatiquement. `fork-manifest.json` conserve les
commits, empreintes et adaptations de la référence. Le marquage de distribution n’affecte pas le choix
de sa base : celle-ci reste une vraie release officielle stable.

Les branches `feat/...` destinées à Wuki60 restent séparées des réglages propres à ce dépôt. La PR JSON
nº 16 ne contient pas le sélecteur. Les commits et fusions utilisent `maxime.huran@gmail.com`.

GitHub peut retarder une action programmée ou la désactiver après 60 jours sans activité. L’onglet
Actions permet de vérifier son état, la réactiver et lancer **Run workflow**.

## HTTPS Android 6 et installation initiale

Le mécanisme de mise à jour ajoute à la confiance système les racines publiques **Sectigo E46** et
**ISRG Root X1**, limitées aux connexions de mise à jour. Les certificats, leurs dates, les noms des
serveurs et les signatures APK restent vérifiés. Les redirections doivent rester HTTPS. Aucun
certificat n’est installé dans Android et aucune vérification TLS n’est désactivée.

Sources et empreintes :

- [Racines Sectigo](https://www.sectigo.com/knowledge-base/detail/Sectigo-Public-Intermediates-and-Roots), E46 SHA-256 `c90f26f0fb1b4018b22227519b5ca2b53e2ca5b3be5cf18efe1bef47380c5383`.
- [ISRG Root X1](https://letsencrypt.org/certificates/), SHA-256 `96bcec06264976f37460779acf28c5a7ccfe8a3c0aae11a8ffcee05c0bddf08c6`.

Une ancienne version incapable de télécharger via HTTPS doit recevoir une première fois le nouvel
APK depuis le Mac, avec sauvegarde préalable des données et de l’APK installé. Ensuite les mises
à jour et changements d’édition passent par le Wi-Fi. Cette compilation doit encore être essayée
sur le robot réel ; un test d’émulateur ne valide pas le fonctionnement physique du moteur.

Pour un retour à l’APK original Wuki60 0.5.1, distinct de la référence équipée du sélecteur, restaurer
aussi les données d’avant le fork : sa base version 1 ne peut pas ouvrir la base version 2 du fork.

## Options communautaires de l’édition Fork

Les [PR nº 2](https://github.com/Wuki60/MrCuisineVibeConnect/pull/2) et
[nº 4](https://github.com/Wuki60/MrCuisineVibeConnect/pull/4) de pierok13 sont reprises intégralement
dans Fork Maxime, sans changer leurs valeurs par défaut. La référence conserve son code officiel.

- **Sécurité → Inverser le capteur du bol** : désactivé par défaut. Vérifier l’indication bol en place
  puis retiré avant de réactiver **Exiger la détection du bol**. Le verrouillage du couvercle reste actif.
- **Écran → Mise en veille** : 10 minutes par défaut, ou Jamais / 2 / 5 / 10 / 15 / 30 minutes.
  Écran noir avec heure mobile, rétroéclairage minimum et indication de bol chaud. Pas de veille
  pendant un programme (pause et turbo compris) ou sur la balance. Le premier geste réveille sans
  déclencher une action. La molette reste en veilleuse blanche faible et fixe par défaut.
- Les listes déroulantes affichent immédiatement la nouvelle sélection, comme dans la PR nº 4.

Les commits originaux conservent leur auteur ; leur intégration utilise `maxime.huran@gmail.com`
comme adresse de commit. Ces options ne sont pas ajoutées à la PR indépendante des sources JSON.
