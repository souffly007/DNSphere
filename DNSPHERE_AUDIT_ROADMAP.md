DNSphere — Audit & Roadmap d'amélioration

Projet : DNSphere
Package : "fr.bonobo.dnsphere"
Objectif : améliorer, simplifier et fiabiliser DNSphere sans supprimer de fonctionnalités utiles.

---

1. RÈGLE PRINCIPALE

Avant toute modification :

- Ne supprimer aucune fonction uniquement parce qu'elle semble faire doublon.
- Vérifier tous les appels et dépendances avant suppression.
- Préserver le fonctionnement actuel du VPN et du filtrage DNS.
- Ne pas réécrire le moteur DNS fonctionnel sans raison démontrée.
- Procéder par petites modifications testables.
- Compiler et tester après chaque groupe de changements.
- Privilégier la simplification plutôt que l'ajout de nouvelles couches.

L'objectif n'est pas de refaire DNSphere de zéro.

L'objectif est de transformer une application devenue très complète par ajouts successifs en une application plus claire, maintenable et robuste.

---

2. PRIORITÉ CRITIQUE — PROFILS ET PLANIFICATION

Constat

DNSphere possède plusieurs mécanismes liés aux profils et à leur activation :

- "Profile"
- "ProfileSchedule"
- "ProfileDao"
- "ProfileScheduleDao"
- "ProfilesActivity"
- "ProfileScheduleActivity"
- "ProfileScheduler"
- "ScheduleReceiver"
- WorkManager
- alarmes Android

Le fonctionnement actuel doit être analysé pour déterminer si plusieurs systèmes assurent la même fonction.

Point particulier à vérifier

"ProfileScheduler" semble utiliser un "PeriodicWorkRequest" avec une période de 15 minutes alors que la logique/commentaire laisse entendre une vérification beaucoup plus fréquente.

Conséquence potentielle :

Un profil programmé à 20:00 pourrait ne pas être appliqué exactement à 20:00.

Objectif

Conserver un système de programmation clairement défini.

Architecture souhaitée :

Profil
   ↓
Planification
   ↓
Activation du profil
   ↓
Politique de filtrage
   ↓
VPN / moteur DNS

Évaluer s'il faut privilégier :

- AlarmManager pour les changements programmés précis ;
- WorkManager pour maintenance/vérification de secours ;
- ou une combinaison justifiée des deux.

Éviter deux moteurs indépendants essayant d'activer les mêmes profils.

---

3. PRIORITÉ CRITIQUE — DOUBLONS DE FILTRAGE

Analyser les interactions entre :

- BlockListManager
- ParentalManager
- RulesEngine
- SafeSearch
- règles personnalisées
- listes externes
- filtrage par application
- listes forcées
- whitelist
- blocage DoH
- blocage WebRTC/STUN

Question centrale

Plusieurs composants peuvent-ils décider séparément de bloquer le même domaine ?

Exemple actuel potentiel :

requête DNS
   ↓
RulesEngine
   ↓
BlockListManager
   ↓
ParentalManager
   ↓
SafeSearch
   ↓
AppFilter

Cela peut provoquer :

- code dupliqué ;
- règles contradictoires ;
- statistiques incorrectes ;
- difficulté à déterminer pourquoi un domaine a été bloqué.

Architecture cible

Créer progressivement un moteur de décision centralisé :

DNS Query
    ↓
FilterEngine
    ↓
┌────────────────────────┐
│ Whitelist              │
│ User Rules             │
│ Parental Rules         │
│ App Rules              │
│ Block Lists            │
│ Security Rules         │
│ SafeSearch             │
└────────────────────────┘
    ↓
Decision

Une décision devrait retourner quelque chose comme :

ALLOW
BLOCK
REDIRECT

avec une raison :

ADS
TRACKER
MALWARE
PARENTAL
USER_RULE
APP_BLOCK
DOH_BYPASS
SAFE_SEARCH
EXTERNAL_LIST

---

4. PRIORITÉ HAUTE — LOCALVPNSERVICE

"LocalVpnService" est devenu l'un des fichiers centraux les plus complexes du projet.

Il assure actuellement de nombreuses responsabilités :

- création du VPN ;
- traitement réseau ;
- DNS ;
- résolveurs ;
- filtrage ;
- cache ;
- statistiques ;
- contrôle parental ;
- SafeSearch ;
- règles par application ;
- notifications ;
- logs ;
- pause/reprise ;
- configuration.

Risque

Un changement dans une fonctionnalité peut provoquer une régression dans une autre.

Objectif

Ne PAS réécrire "LocalVpnService".

Le découper progressivement.

Architecture possible :

LocalVpnService
│
├── VpnTunnelManager
├── DnsPacketProcessor
├── ResolverManager
├── DnsCache
├── FilterEngine
├── StatsManager
└── NotificationController

"LocalVpnService" doit progressivement devenir un orchestrateur et non contenir toute la logique de DNSphere.

---

5. PRIORITÉ HAUTE — STATISTIQUES

Vérifier les compteurs :

- Ads
- Trackers
- Malware
- Shopping
- Parental
- App blocking
- External lists
- DoH bypass
- WebRTC/STUN
- User rules

Certaines catégories semblent actuellement regroupées dans des compteurs qui ne correspondent pas à leur signification.

Exemple :

Un blocage parental ne devrait jamais augmenter un compteur nommé "shoppingBlocked".

Solution recommandée

Créer une classification centrale :

enum class BlockReason {
    ADS,
    TRACKER,
    MALWARE,
    SHOPPING,
    PARENTAL,
    EXTERNAL,
    APP_BLOCK,
    USER_RULE,
    DOH_BYPASS,
    WEBRTC_STUN,
    OTHER
}

Les statistiques pourraient ensuite être calculées à partir de cette classification.

---

6. PRIORITÉ HAUTE — CONTRÔLE PARENTAL

"ParentalManager" est très complet.

Il doit cependant être vérifié pour savoir quelles responsabilités sont également présentes dans :

- Profiles
- RulesEngine
- BlockListManager
- SafeSearch
- AppFilter

Le contrôle parental devrait devenir une politique de filtrage, et non un second moteur DNS.

Architecture souhaitée :

Profil Enfant
      ↓
ParentalPolicy
      ↓
FilterEngine

Un profil pourrait définir :

Ads             ON
Trackers        ON
Malware         ON
Adult           ON
Social          ON/OFF
Gaming          ON/OFF
Streaming       ON/OFF
SafeSearch      ON
CustomLists     ON

---

7. PRIORITÉ HAUTE — SÉCURITÉ RÉSEAU

Le Manifest contient :

android:usesCleartextTraffic="true"

Déterminer précisément pourquoi.

Si aucune fonctionnalité n'exige HTTP non chiffré :

android:usesCleartextTraffic="false"

devrait être privilégié.

Ne pas modifier avant d'avoir identifié :

- téléchargements de listes ;
- API éventuelles ;
- DNS spécifiques ;
- ressources HTTP ;
- fonctions de diagnostic.

---

8. AUDIT DES PERMISSIONS

Examiner individuellement :

QUERY_ALL_PACKAGES
SCHEDULE_EXACT_ALARM
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
USE_BIOMETRIC

Pour chaque permission :

1. Où est-elle utilisée ?
2. Est-elle indispensable ?
3. Existe-t-il une API Android plus restrictive ?
4. Que se passe-t-il si elle est refusée ?
5. Est-elle compatible avec une future publication sur différents stores ?

Principe DNSphere :

«Demander le minimum de permissions nécessaire.»

---

9. AUDIT DES LISTES DE BLOCAGE

Analyser toutes les sources :

- listes embarquées ;
- listes téléchargées ;
- listes utilisateur ;
- listes parentales ;
- domaines SafeSearch ;
- listes DoH ;
- listes WebRTC/STUN ;
- domaines spécifiques aux applications.

Chercher :

- domaines présents plusieurs fois ;
- listes identiques ;
- règles contradictoires ;
- domaines whitelistés et bloqués simultanément ;
- données obsolètes ;
- listes chargées plusieurs fois en mémoire.

---

10. OPTIMISATION BLOCKLISTMANAGER

"BlockListManager" est puissant mais doit être audité pour :

- consommation mémoire ;
- chargement au démarrage ;
- fréquence des reloads ;
- recherche de domaines ;
- normalisation ;
- doublons ;
- allocations inutiles.

Pour chaque domaine, utiliser une normalisation commune :

lowercase
trim
suppression éventuelle du point final DNS
normalisation IDN si nécessaire

Éviter que :

Example.com
example.com
example.com.

soient traités comme trois entrées différentes.

---

11. CACHE DNS

Le cache basé sur les TTL DNS est une bonne base.

À conserver.

Vérifier néanmoins :

- TTL minimum ;
- TTL maximum ;
- réponses NXDOMAIN ;
- cache négatif ;
- purge lors d'un changement de DNS ;
- purge lors d'un changement de profil ;
- limites mémoire ;
- concurrence entre threads.

Ne pas remplacer par un cache à durée fixe sans justification.

---

12. RÉSOLVEURS DNS

Préserver les différents protocoles pris en charge.

Auditer :

DoH
DoT
DoQ
DoH3

Vérifier que le code commun n'est pas dupliqué entre les implémentations.

Créer si nécessaire une interface commune :

interface DnsResolver {
    suspend fun resolve(query: ByteArray): ByteArray?
}

Les implémentations spécifiques doivent gérer uniquement leur protocole.

---

13. DNS PROVIDERS

Vérifier la liste des fournisseurs et éviter plusieurs définitions différentes dans plusieurs écrans.

Une source unique devrait alimenter :

- paramètres ;
- sélecteur rapide ;
- notification ;
- écran principal.

Exemple :

DnsProviderRepository

avec :

name
protocol
hostname
endpoint
description
familySafe
adBlocking

Objectif :

Ajouter un fournisseur une seule fois et le voir apparaître partout.

---

14. NETTOYAGE DES RESTES PHONEZEN

Rechercher globalement :

PhoneZen
stopdemarchage
anciens packages
anciens commentaires
anciens copyrights

Certains fichiers DNSphere contiennent encore des références à PhoneZen.

Nettoyer uniquement les commentaires/headers qui n'ont aucune incidence fonctionnelle.

---

15. CODE MORT

Faire une analyse complète :

- classes jamais instanciées ;
- fonctions jamais appelées ;
- variables inutilisées ;
- anciens receivers ;
- anciens workers ;
- anciennes activities ;
- anciens layouts ;
- anciennes ressources ;
- anciennes icônes ;
- dépendances Gradle inutilisées.

IMPORTANT :

Ne jamais supprimer uniquement parce qu'Android Studio indique "unused".

Vérifier :

- Manifest ;
- reflection ;
- Room ;
- BroadcastReceiver ;
- WorkManager ;
- services Android ;
- intents.

---

16. DOUBLONS À RECHERCHER

Audit spécifique sur :

ProfileScheduler / ScheduleReceiver
RulesEngine / BlockListManager
ParentalManager / Profiles
SafeSearch / ParentalManager
AppFilter / RulesEngine
BlockLog / Stats
DNS providers / QuickDNSSelector
Whitelist / Rules ALLOW
Blacklist / Rules BLOCK

Pour chaque doublon potentiel, classer :

PAS DOUBLON
DOUBLON PARTIEL
DOUBLON RÉEL
ANCIEN CODE
À FUSIONNER
À SUPPRIMER

---

17. INTERFACE

Conserver l'interface actuelle qui a déjà été retravaillée.

Ne pas refaire l'UI pendant la refactorisation interne.

Priorités UI restantes :

- compteur de blocages en temps réel ;
- cohérence des statistiques ;
- affichage du DNS actif ;
- profils compréhensibles ;
- état VPN clair ;
- indication d'un changement automatique de profil.

---

18. BATTERIE

Mesurer avant d'optimiser.

Surveiller :

- wakeups ;
- WorkManager ;
- AlarmManager ;
- boucle VPN ;
- statistiques ;
- écriture Room ;
- notifications ;
- téléchargement des listes.

Éviter les timers permanents si un événement Android peut faire le même travail.

---

19. LOGS ET BASE ROOM

Éviter une croissance illimitée.

Prévoir :

- limite de rétention ;
- nettoyage périodique ;
- agrégation des statistiques ;
- suppression des anciens logs.

Exemple :

logs détaillés : 7/30 jours
statistiques agrégées : conservation longue

La durée exacte devra rester configurable ou adaptée au besoin réel.

---

20. TESTS À AJOUTER

Tests prioritaires :

Domaine bloqué

ads.example.com
→ BLOCK / ADS

Whitelist prioritaire

domaine présent blacklist + whitelist
→ ALLOW

Parental

adult.example
→ BLOCK / PARENTAL

Changement profil

Normal → Enfant
→ nouvelles règles immédiatement actives

Changement DNS

Cloudflare → Mullvad
→ cache correctement invalidé si nécessaire

Statistiques

PARENTAL
→ compteur parental
→ pas compteur shopping

---

21. README

Le README ne représente plus toutes les capacités actuelles de DNSphere.

Le réécrire après stabilisation.

Présenter notamment :

- VPN DNS local ;
- confidentialité ;
- DoH/DoT/DoQ/DoH3 ;
- filtrage ;
- profils ;
- contrôle parental ;
- SafeSearch ;
- règles personnalisées ;
- DNS par application ;
- statistiques ;
- listes externes ;
- sélecteur rapide.

---

22. LICENCE

Clarifier la licence avant une distribution plus large.

Le projet annonce GPL-3.0 tout en ajoutant une restriction sur l'utilisation commerciale.

Vérifier la compatibilité juridique de cette restriction avec la GPL.

Ne pas modifier automatiquement la licence.

Décision du développeur obligatoire.

---

23. ORDRE RECOMMANDÉ DES TRAVAUX

Phase 1 — Aucun changement fonctionnel

- détecter code mort ;
- détecter doublons ;
- nettoyer anciens commentaires ;
- cartographier dépendances ;
- vérifier permissions.

Phase 2 — Bugs

- statistiques ;
- actualisation compteurs ;
- profils ;
- programmation ;
- incohérences UI/état.

Phase 3 — Architecture

Extraire progressivement :

FilterEngine
ResolverManager
StatsManager
NotificationController
DnsPacketProcessor

Phase 4 — Profils

Unifier :

Profiles
Parental
SafeSearch
Scheduling

autour d'une politique de filtrage cohérente.

Phase 5 — Sécurité

- cleartext ;
- permissions ;
- stockage ;
- téléchargements ;
- validation des listes ;
- protection des paramètres sensibles.

Phase 6 — Tests

Ajouter tests unitaires et tests d'intégration avant toute grosse évolution supplémentaire.

---

24. CE QU'IL NE FAUT PAS FAIRE

❌ Réécrire DNSphere de zéro.

❌ Remplacer le moteur VPN fonctionnel sans nécessité.

❌ Supprimer une classe simplement parce qu'elle semble ancienne.

❌ Fusionner Parental/Profile/Rules en une seule énorme classe.

❌ Ajouter encore des fonctions directement dans "LocalVpnService".

❌ Optimiser sans mesurer.

❌ Modifier simultanément architecture + interface + DNS.

---

25. OBJECTIF FINAL

Architecture souhaitée à terme :

                 DNSphere
                    │
              LocalVpnService
                    │
        ┌───────────┴───────────┐
        │                       │
 DnsPacketProcessor       ResolverManager
        │                       │
        │                 ┌─────┼─────┐
        │                DoH   DoT   DoQ/DoH3
        │
   FilterEngine
        │
 ┌──────┼──────────────────────┐
 │      │       │       │      │
Rules Profiles Parental Lists Apps
        │
        ↓
   BlockDecision
        │
 ┌──────┴──────┐
 │             │
StatsManager  BlockLog

Le principe :

«Une requête DNS → une décision centralisée → une raison identifiable.»

Cela simplifiera :

- le débogage ;
- les statistiques ;
- les profils ;
- le contrôle parental ;
- l'ajout de nouvelles listes ;
- les tests ;
- la maintenance.

---

CONCLUSION

DNSphere ne nécessite pas une reconstruction complète.

La base est intéressante et le projet possède déjà beaucoup de fonctionnalités avancées.

Le principal problème est plutôt une conséquence normale de son évolution : plusieurs fonctions ont été ajoutées progressivement et certaines responsabilités commencent à se chevaucher.

La prochaine évolution doit donc privilégier :

1. Simplification
2. Suppression des vrais doublons
3. Fiabilisation des profils
4. Centralisation du filtrage
5. Découpage de LocalVpnService
6. Correction des statistiques
7. Sécurité
8. Tests

Une fois cette phase terminée, DNSphere disposera d'une base beaucoup plus saine pour accueillir de nouvelles fonctionnalités sans augmenter inutilement sa complexité.
