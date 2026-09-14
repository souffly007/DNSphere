# DNSphere — Préparation du découpage architectural

## État constaté

`LocalVpnService.kt` contient actuellement 1 252 lignes et plusieurs responsabilités distinctes :

1. cycle de vie du service VPN ;
2. lecture et traitement des paquets IP/TCP/UDP ;
3. extraction et reconstruction DNS ;
4. filtrage et classification des domaines ;
5. résolution DoH/DoT/DoQ/DoH3 ;
6. cache DNS ;
7. statistiques et journaux ;
8. notifications et actions rapides ;
9. gestion de pause/reprise et watchdog.

## Découpage recommandé

### Étape 1 — `NotificationController`

Extraction à faible risque des éléments suivants :

- création du canal de notification ;
- notification de protection interrompue ;
- notification principale ;
- mise à jour du texte et du fournisseur DNS ;
- actions pause/reprise et whitelist.

Cette étape ne change ni le tunnel VPN ni la décision de filtrage.

### Étape 2 — `StatsController`

Regrouper :

- compteurs par catégorie ;
- publication de `VpnStats` ;
- écriture des `BlockLog` ;
- remise à zéro lors d'un nouveau démarrage.

Les valeurs et la notification devront rester identiques après extraction.

### Étape 3 — `DnsPacketProcessor`

Extraire progressivement :

- extraction de la question DNS ;
- identification du type de requête ;
- création d'une réponse bloquée ;
- reconstruction/checksum des paquets.

Cette étape demande des tests ciblés sur IPv4, TCP, UDP, réponses bloquées et SafeSearch.

### Étape 4 — `ResolverManager`

Centraliser le choix entre DNS standard, DoH, DoT, DoQ et DoH3 sans modifier les URLs ni les fournisseurs existants.

## Règle de sécurité

Une seule extraction à la fois, compilation et test après chaque étape. Ne pas modifier simultanément le filtrage, l'interface et le moteur VPN.

## Premier chantier recommandé

Commencer par `NotificationController`, car c'est le module le moins risqué et le plus indépendant du traitement des paquets. Le découpage du processeur DNS viendra seulement après validation des tests de profils et de filtrage.

