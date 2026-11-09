# DNSphere 1.5g (code 2)

DNSphere 1.5g remplace l'unique choix Quad9 de sécurité standard par HaGeZi Sécurité et ajoute HaGeZi Protection complète. Sécurité utilise `https://ctif.hagezi.org/dns-query` et bloque les domaines malveillants, le phishing et les arnaques. Protection complète utilise `https://root.hagezi.org/dns-query`, avec `https://wurzn.hagezi.org/dns-query` en secours de même niveau, et bloque aussi les publicités et traqueurs. Le filtrage DNS ne bloque pas toutes les publicités, notamment celles intégrées au contenu. Ces choix ne sont pas des protections parentales ; le profil Enfants et ses règles restent distincts.

Les adresses et les IP de démarrage TLS ont été vérifiées dans le [dépôt officiel HaGeZi](https://github.com/hagezi/dns-servers). Le moteur envoie des messages DNS réels sur HTTPS, garde la validation TLS/nom d'hôte et ne se replie ni vers le DNS non chiffré ni vers un fournisseur moins filtrant pour HaGeZi. Une réponse DNS de filtrage normale ne déclenche pas le secours. La notification et l'accueil signalent l'indisponibilité du service DNS ; le choix d'un autre fournisseur reste accessible.

Le code source présent avant modification indiquait `1.5f` (code 1), ce qui correspond à la version installée sur le téléphone connecté. Les anciens APK de `app/release` indiquent `1.5e` (code 1). Le prochain numéro est donc `1.5g` (code 2), avec l'identifiant d'application `fr.bonobo.dnsphere` conservé.

## Migration

Les références de fournisseur `quad9` dans les préférences DNSphere, VPN et Preferences sont transformées en `hagezi-security`. Les autres clés et réglages sont conservés. Un message unique est présenté après migration. Le seul identifiant Quad9 du catalogue était `quad9` ; les variantes inconnues ne sont pas réattribuées arbitrairement. Les profils et horaires Room contiennent des règles et des identifiants de profil, pas de fournisseur DNS. Le profil Enfants demeure inchangé. Les anciennes références Quad9 gardées dans les listes anti-contournement servent à intercepter le DNS direct : ce ne sont pas des résolveurs de secours.

## Fichiers touchés pour cette version

Catalogue et migration : `app/src/main/java/fr/bonobo/dnsphere/data/{DnsProviderCatalog,ProviderMigration,ProviderPreferencesMigration}.kt`, `DnsphereApplication.kt`, `BackupManager.kt`. Moteur et état : `dns/{DohResolver,DohBootstrap,DohResponsePolicy}.kt`, `ResolverManager.kt`, `LocalVpnService.kt`, `NotificationController.kt`. Interface : `MainActivity.kt`, `ui/{SettingsActivity,Quickdnsselectoractivity}.kt`, `app/src/main/res/values*/strings.xml`, `app/src/main/res/values/strings_dns_providers.xml`, `app/src/main/res/values/arrays.xml`, `app/src/main/res/layout/activity_settings.xml`. Anciennes branches Quad9 : `network/{DohResolver,DotResolver,Doh3resolver}.kt`, `utils/PrefsManager.kt`. Livraison et contrôle : `app/build.gradle.kts`, `README.md`, `.gitignore`, `tools/{check_hagezi_doh,sign_release}.py`, `app/src/test/java/fr/bonobo/dnsphere/HageziTest.kt`.

Un ancien doublon local de `AppsActivity.kt` empêchait toute compilation ; sa copie est conservée dans `delivery/Appsactivity.kt.preexisting-copy` en dehors de l'arbre des sources.

## Contrôles effectués

- `testDebugUnitTest` : 24 tests réussis, zéro échec, dont migration, réponses filtrées, SERVFAIL, requêtes A et AAAA.
- Requêtes DNS-over-HTTPS filaires réelles vers `ctif`, `root` et `wurzn` : A et AAAA pour `example.com` avec réponses valides ; A et AAAA pour le canari inoffensif `use-application-dns.net` avec NXDOMAIN. Résultats détaillés dans `doh-network-checks.json`. Ces essais ne traversent pas le VPN Android.
- `assembleDebug` et `assembleRelease` : exécutés avec succès. L’APK Debug signé et l’APK Release non signé portent `1.5g` (code 2), applicationId inchangé. Le build Release Gradle est non signé ; `tools/sign_release.py` signe avec `bonobo.jks` quand `signing.local.properties` est fourni localement et vérifie le certificat de production attendu.
- `lintDebug` échoue encore sur 30 erreurs préexistantes (traductions manquantes des règles, permission QUERY_ALL_PACKAGES, mise en page RTL) et 461 avertissements. L’indentation signalée dans le tunnel et les deux nouvelles ressources ont été corrigées puis vérifiées par le second passage de Lint. Le rapport est dans `app/build/reports/lint-results-debug.html`.

## Signature et installation

Le téléphone connecté contient DNSphere 1.5f signé avec un certificat Android Debug de SHA-256 `6bee8bbadaa5ab822ad42391f10e6d4e70d3eb8b16f3f30c13132581271f2926`. La clé Debug locale a un autre certificat (`1723fc2d56b64317505fd6925763e52399905010847c23fc95c9eaf6b3b2ca60`). Les anciens APK de production du dépôt ont le certificat `a2723a3cc2b325db18d8f4b662ec262efbcf6954d8ace8a78e59b9176a7e55f1`. La mise à jour sur place du téléphone connecté nécessite la clé Debug originelle. L'APK Debug compilé avec la clé locale peut être installé sur un appareil compatible sans cette installation existante, mais il ne mettra pas à jour ce téléphone.

Pour signer l'APK de production, créer localement un fichier `signing.local.properties` non inclus dans le ZIP, contenant `storePassword`, `keyAlias`, `keyPassword`, et facultativement `storeFile` (par défaut `/var/home/Snoopy63/Documents/bonobo.jks`), puis lancer `python3 tools/sign_release.py`. Le script n'exporte la version de production que si son certificat correspond à l'ancien APK de production.

## Essais restant sur téléphone après signature compatible

Mise à jour sans perte de paramètres ; migration d'un choix Quad9 dans les préférences puis vérification d'un profil planifié ; activation/désactivation des deux profils ; requêtes A/AAAA via le tunnel ; filtrage réel ; Wi-Fi/données mobiles ; veille/reprise ; interruption de `root` et usage de `wurzn` ; régressions Mullvad, DNS4EU, horaires et Enfants ; contrôle d'absence de fuite IPv4 et IPv6 dans les scénarios VPN pris en charge. Aucune installation de test ni changement des préférences n'a été effectué sur le téléphone connecté.
