# DNSphere — Cartographie des priorités de filtrage

Cette étape est volontairement documentaire : aucun comportement n'est modifié.

## Ordre actuel observé

1. Pause VPN : transfert DNS sans filtrage.
2. Règle d'application `BLOCK_ALL` : blocage immédiat.
3. Règle d'application `ALLOW_ALL` : transfert immédiat sans filtrage.
4. SafeSearch du contrôle parental : réponse redirigée si le moteur est concerné.
5. `FilterEngine` : décision standard avec whitelist, bypass, parental, règles utilisateur, STUN et catégories.
6. Cache DNS uniquement après une décision `ALLOW`.
7. Résolution DNS puis mise en cache selon le TTL.

## Priorités internes de `FilterEngine`

1. Domaine exempté / whitelist : `ALLOW`.
2. Contournement DNS connu : `DOH_BYPASS`.
3. Contrôle parental actif : `PARENTAL`.
4. Blocage forcé ou règle utilisateur : `FORCE_BLOCKED`.
5. STUN/WebRTC : `WEBRTC_STUN`.
6. Publicités, trackers, malwares, shopping selon les options.
7. Listes externes.
8. Autorisation.

## Points à traiter

- `BLOCK_ALL` et `ALLOW_ALL` sont encore traités directement dans `LocalVpnService`.
- SafeSearch est encore traité directement avant `FilterEngine`.
- Les décisions `BLOCK`, `ALLOW` et `REDIRECT` ne partagent pas encore un type commun.
- La raison statistique du blocage est ajoutée après la décision dans le service.
- Le cache intervient correctement après l'autorisation, mais l'invalidation lors d'un changement de profil/DNS devra être vérifiée.

## Proposition sûre

Créer un `FilteringPolicy` ou `FilteringCoordinator` qui retourne une décision unique :

- `ALLOW` ;
- `BLOCK(reason)` ;
- `REDIRECT_SAFE_SEARCH(response)` ;
- `BYPASS_APP`.

Le service conservera la construction de la réponse et l'écriture des statistiques dans un premier temps. Cette séparation évitera de fusionner `Profiles`, `Parental`, `Rules` et `AppFilterManager` dans une classe unique.

## Tests obligatoires avant intégration

- whitelist prioritaire sur une liste de blocage ;
- règle `BLOCK_ALL` ;
- règle `ALLOW_ALL` ;
- contrôle parental actif/inactif ;
- SafeSearch ;
- règle utilisateur ;
- blocage publicités/trackers ;
- pause VPN ;
- cohérence des raisons dans les statistiques.

