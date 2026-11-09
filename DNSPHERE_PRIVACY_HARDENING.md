# DNSphere — Durcissement de la confidentialité DNS

Date : 3 octobre 2026 · Base : archive `DNSphere-master` fournie · Licence : GPL v3

## Ce qui change

| Sujet | Avant | Après |
|---|---|---|
| Repli en clair | Si DoH/DoT/DoQ/DoH3 échouait, `ResolverManager` repartait en UDP/53 sans prévenir | Option **Mode strict** : la requête reçoit un SERVFAIL, rien ne part en clair |
| Repli UDP caché de DoQ | `DoqResolver` retombait sur 1.1.1.1:53 en clair | Interdit en mode strict (`allowUdpFallback`) |
| Certificat DoT | Nom d'hôte non vérifié (socket TLS brut) | Vérification `endpointIdentificationAlgorithm = "HTTPS"` + `HostnameVerifier` |
| Connexions DoT | Une nouvelle session TLS par requête | Connexion réutilisée (fermée après 8 s d'inactivité, une nouvelle tentative si périmée) |
| Nom du résolveur (Rethink, URL perso, DoT perso) | Résolu par le DNS système, donc en clair (l'app est exclue de son VPN) | `DohBootstrap` : résolu en DoH vers 1.1.1.1 / 9.9.9.9 (IP littérales) ; repli système interdit en mode strict |
| Longueur des requêtes | Visible par un observateur du canal chiffré | Padding EDNS (RFC 8467), multiples de 128 octets |
| Sous-réseau client | Transmis tel quel | ECS avec préfixe 0 (RFC 7871) ; ECS/padding existants remplacés |

Nouvelles préférences (écran Paramètres, section DNS sécurisé) :

- `dns_query_protection` — padding + ECS, **activé par défaut**.
- `dns_strict_mode` — **désactivé par défaut** pour ne pas casser la navigation sur les réseaux qui filtrent DoH/DoT.

Les deux sont lues à chaque requête dans `vpn_prefs` : aucun redémarrage du VPN n'est nécessaire.

## Fichiers

Nouveaux : `dns/DnsWire.kt`, `dns/DnsQuerySanitizer.kt`, `dns/DohBootstrap.kt`, `dns/PrivacyPrefs.kt`,
`res/values*/strings_dns_privacy.xml` (11 langues), `test/.../DnsQuerySanitizerTest.kt`.

Modifiés : `ResolverManager.kt`, `DnsResponseBuilder.kt` (SERVFAIL), `LocalVpnService.kt` (câblage),
`network/DotResolver.kt` (réécrit), `network/DoqResolver.kt`, `dns/DohResolver.kt`, `res/xml/preferences.xml`.

## Vérifications faites

- `DnsWire` et `DnsQuerySanitizer` compilés et testés (8 tests : padding, ECS remplacé, options inconnues et bit DO conservés, requêtes malformées inchangées, OPT ajouté retiré de la réponse).
- `DotResolver`, `DohBootstrap`, `PrivacyPrefs`, `ResolverManager`, `DnsResponseBuilder` : typage vérifié avec des stubs Android/OkHttp.
- **Non fait ici** : compilation Gradle complète, test réseau réel. À faire sur le Poco X7 Pro.

## Tests recommandés sur l'appareil

1. `./gradlew testDebugUnitTest --tests "*DnsQuerySanitizerTest"` puis `assembleDebug`.
2. DoH Cloudflare, mode strict OFF puis ON : la navigation doit rester normale.
3. Mode strict ON + coupure du port 443 vers 1.1.1.1 (ou DoT sur un réseau qui bloque 853) : les pages échouent vite, sans requête UDP/53 (vérifiable avec `adb shell tcpdump` ou un capteur côté routeur).
4. Rethink : vérifier dans le logcat (`DohBootstrap`) que le nom est résolu sans repli système.
5. Un site utilisant EDNS/DNSSEC (`dig +dnssec` depuis Termux) : la réponse doit rester valide.

## Limites connues (non traitées ici)

- **Le résolveur voit toujours le domaine** (sauf ODoH, non implémenté). Ce travail réduit les fuites autour, pas celle-ci.
- Le SNI TLS reste en clair sans ECH, et l'IP de destination reste visible du FAI.
- `Doh3Resolver` / `DoqResolver` passent par **Cronet** : la résolution du nom du serveur y utilise le DNS système (fuite du nom du résolveur, pas des sites).
- `build.gradle.kts` contient `com.google.android.gms:play-services-cronet`, une dépendance **propriétaire** incompatible avec F-Droid et avec votre règle « aucune dépendance propriétaire ». Alternative libre : `org.chromium.net:cronet-embedded`, ou retirer DoQ/DoH3.
- `DoqResolver` n'est pas du vrai DoQ : il envoie du DoH3 sur `https://<serveur>/dns-query`.
- Le fournisseur « Standard » reste en UDP/53 par conception ; le mode strict ne s'y applique pas.
