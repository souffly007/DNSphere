# DNSphere — Audit des doublons et du code ancien

Date : 10 septembre 2026  
Périmètre : source du ZIP cumulatif après la phase 8  
Principe : audit sans changement fonctionnel

## Résumé

Le projet ne présente pas de doublon évident entre deux activités ou deux services actifs. En revanche, plusieurs zones héritées ou redondantes doivent être clarifiées avant un nettoyage :

| Élément | État | Risque | Action recommandée |
|---|---|---:|---|
| `network/DohResolver.kt` | supprimé après confirmation d'absence de références | — | terminé |
| `dns/DohResolver.kt` | utilisé par `LocalVpnService` | élevé si modifié | conserver |
| `Profilescheduler.kt` | worker périodique + worker de frontière | moyen | conserver pendant le test utilisateur |
| `RulesEngine` / `BlockListManager` | responsabilités imbriquées | élevé | ne pas fusionner maintenant |
| `ParentalManager` / `SafeSearchEnforcer` | complémentaires, pas doublons stricts | moyen | documenter la priorité |
| `AppFilterManager` | filtrage par application distinct | moyen | conserver, tester les priorités |
| `Appsactivity.kt` | nom de fichier ancien/non conventionnel | faible | renommage éventuel plus tard |
| anciens commentaires / en-têtes | présents dans plusieurs fichiers | faible | nettoyage documentaire uniquement |

## 1. Planification des profils

### État constaté

La planification active est centralisée dans `ProfileSchedulerWorker` (`Profilescheduler.kt`). Elle utilise :

- un travail périodique WorkManager toutes les 15 minutes ;
- un travail unique au prochain début ou à la prochaine fin de créneau ;
- une évaluation immédiate au démarrage de l'application ;
- une reprogrammation après ajout, activation, désactivation ou suppression d'un créneau.

Aucune classe `ScheduleReceiver` ou programmation `AlarmManager` active n'a été retrouvée dans le projet actuel. Les anciennes références Manifest ont déjà été supprimées.

### Conclusion

Il ne faut pas supprimer le worker périodique maintenant : il sert de filet de sécurité si le travail de frontière est retardé ou perdu par Android. Le test nocturne de l'utilisateur doit être terminé avant toute simplification.

## 2. Fournisseurs DNS et résolveurs

### Doublon identifié

Deux classes portent le même nom simple `DohResolver` :

- `dns/DohResolver.kt` : utilisée par `LocalVpnService` et adaptée au fonctionnement actuel ;
- `network/DohResolver.kt` : aucune référence trouvée dans le code ou le Manifest.

### Recommandation

`network/DohResolver.kt` est un candidat sérieux au code ancien. Suppression possible après une vérification finale de recherche globale et une compilation propre. Il ne faut pas toucher à `dns/DohResolver.kt`.

Les résolveurs DoT, DoQ et DoH3 sont utilisés par `LocalVpnService` et doivent être conservés. Le catalogue `DnsProviderCatalog` est maintenant la source commune des fournisseurs affichés et cyclés.

## 3. Filtrage et règles

### `RulesEngine` / `BlockListManager`

Ce n'est pas un doublon strict :

- `RulesEngine` analyse les règles utilisateur de type AdGuard/regex ;
- `BlockListManager` charge les listes, la whitelist, le blocage forcé et les catégories ;
- `FilterEngine` orchestre maintenant la décision principale et fournit la raison du blocage.

Cependant, `BlockListManager` contient encore plusieurs méthodes historiques (`isWhitelisted`, tests de catégories et classification) qui peuvent recalculer une partie des mêmes contrôles. C'est une redondance interne à traiter plus tard avec des tests de priorité, pas une suppression immédiate.

### `ParentalManager` / `SafeSearchEnforcer`

Ces composants ne font pas exactement la même chose :

- `ParentalManager` gère la configuration, les horaires et les listes parentales ;
- `SafeSearchEnforcer` redirige certains moteurs de recherche vers leurs IP SafeSearch ou bloque les moteurs non compatibles.

Ils sont néanmoins appelés à plusieurs endroits de `LocalVpnService`. La priorité parentale doit être documentée et testée avant toute centralisation.

### `AppFilterManager`

Le filtrage par UID/application est distinct du filtrage par nom de domaine. Il est utilisé par l'interface et par `LocalVpnService`; ce n'est pas du code mort.

## 4. Interface et fichiers anciens

`Appsactivity.kt` contient la classe active `AppsActivity` référencée par `MainActivity` et le Manifest. Le nom du fichier ne suit simplement pas la convention Kotlin (`AppsActivity.kt`). Renommer le fichier est possible mais ne présente aucun bénéfice fonctionnel immédiat.

Les activités principales déclarées dans le Manifest ont toutes une référence ou un rôle identifiable. Aucun receiver `ScheduleReceiver` ou service `ListUpdateService` orphelin n'est encore déclaré.

## 5. Code probablement inutilisé à confirmer

Les éléments suivants doivent recevoir une vérification dédiée avant suppression :

- `network/DohResolver.kt` — supprimé après confirmation d'absence de références ;
- `GlobalState.kt` — supprimé après confirmation d'absence d'utilisation ;
- `Knownresolverips.kt` — conservé : utilisé par le tunnel VPN ;
- éventuelles méthodes publiques de `BlockListManager` appelées uniquement par d'anciens écrans ou par réflexion.

Une absence de référence textuelle ne suffit pas à supprimer une classe Android : il faut aussi vérifier le Manifest, les intents, Room, WorkManager et les éventuels noms construits dynamiquement.

## 6. Commentaires et entêtes

Plusieurs fichiers contiennent des entêtes ou commentaires hérités mal formatés, par exemple une ligne GPL accolée à une déclaration de classe. Cela n'affecte pas l'exécution, mais nuit à la maintenance. Ce nettoyage est sûr s'il reste limité aux commentaires et ne modifie pas la licence du projet.

## Conclusion et prochaine étape

Le nettoyage immédiat a confirmé et supprimé `network/DohResolver.kt` et `GlobalState.kt`. `Knownresolverips.kt` est conservé car il est utilisé par le tunnel VPN. Le reste doit attendre la validation nocturne des profils et des tests de filtrage.

Ordre conseillé :

1. attendre le retour du test des profils ;
2. confirmer les candidats de code mort ;
3. supprimer uniquement le code confirmé inutilisé — réalisé pour les deux fichiers orphelins ;
4. compiler et tester ;
5. passer ensuite à l'unification progressive de la décision de filtrage.
