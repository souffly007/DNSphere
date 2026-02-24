package fr.bonobo.dnsphere.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import fr.bonobo.dnsphere.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ListImporter(private val context: Context) {

    companion object {
        const val TAG = "ListImporter"

        // Listes populaires pré-configurées
        val POPULAR_LISTS = listOf(
            PopularList(
                "AdGuard DNS Filter",
                "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
                "ADS",
                "Liste officielle AdGuard pour le blocage DNS"
            ),
            PopularList(
                "Peter Lowe's Ad List",
                "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0",
                "ADS",
                "Liste légère et efficace"
            ),
            PopularList(
                "Steven Black Hosts",
                "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
                "ADS",
                "Liste unifiée très populaire"
            ),
            PopularList(
                "OISD Basic",
                "https://basic.oisd.nl/",
                "ADS",
                "Liste optimisée, peu de faux positifs"
            ),
            PopularList(
                "EasyPrivacy",
                "https://v.firebog.net/hosts/Easyprivacy.txt",
                "TRACKERS",
                "Protection anti-tracking"
            ),
            PopularList(
                "URLhaus Malware",
                "https://urlhaus.abuse.ch/downloads/hostfile/",
                "MALWARE",
                "Liste de domaines malveillants"
            )
        )
    }

    private val database = AppDatabase.getInstance(context)

    /**
     * Importer une liste depuis une URL
     */
    suspend fun importFromUrl(
        name: String,
        url: String,
        type: String,
        onProgress: ((Int, Int) -> Unit)? = null
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Importing from URL: $url")

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "DNSphere/1.0")

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext ImportResult.Error("Erreur HTTP: ${connection.responseCode}")
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val domains = mutableSetOf<String>() // Set pour éviter les doublons
                var lineCount = 0

                reader.useLines { lines ->
                    lines.forEach { line ->
                        lineCount++
                        val domain = parseLine(line)
                        if (domain != null) {
                            domains.add(domain)
                        }
                        if (lineCount % 1000 == 0) {
                            onProgress?.invoke(lineCount, domains.size)
                        }
                    }
                }

                connection.disconnect()

                if (domains.isEmpty()) {
                    return@withContext ImportResult.Error("Aucun domaine valide trouvé")
                }

                // Sauvegarder dans la base de données
                val listId = saveList(name, url, type, domains.toList())

                Log.d(TAG, "Imported ${domains.size} domains")
                ImportResult.Success(domains.size, listId)

            } catch (e: Exception) {
                Log.e(TAG, "Import failed", e)
                ImportResult.Error(e.message ?: "Erreur inconnue")
            }
        }
    }

    /**
     * Importer depuis un fichier local
     */
    suspend fun importFromFile(
        name: String,
        uri: Uri,
        type: String
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: return@withContext ImportResult.Error("Impossible d'ouvrir le fichier")

                val reader = BufferedReader(InputStreamReader(inputStream))
                val domains = mutableSetOf<String>()

                reader.useLines { lines ->
                    lines.forEach { line ->
                        val domain = parseLine(line)
                        if (domain != null) {
                            domains.add(domain)
                        }
                    }
                }

                inputStream.close()

                if (domains.isEmpty()) {
                    return@withContext ImportResult.Error("Aucun domaine valide trouvé")
                }

                val listId = saveList(name, null, type, domains.toList())

                ImportResult.Success(domains.size, listId)

            } catch (e: Exception) {
                Log.e(TAG, "Import from file failed", e)
                ImportResult.Error(e.message ?: "Erreur inconnue")
            }
        }
    }

    /**
     * Parser une ligne (supporte plusieurs formats)
     */
    private fun parseLine(line: String): String? {
        val trimmed = line.trim().lowercase()

        // Ignorer les commentaires et lignes vides
        if (trimmed.isEmpty() ||
            trimmed.startsWith("#") ||
            trimmed.startsWith("!") ||
            trimmed.startsWith("//")) {
            return null
        }

        // Format hosts: "0.0.0.0 domain.com" ou "127.0.0.1 domain.com"
        if (trimmed.startsWith("0.0.0.0") || trimmed.startsWith("127.0.0.1")) {
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size >= 2) {
                val domain = parts[1]
                if (isValidDomain(domain)) {
                    return domain
                }
            }
            return null
        }

        // Format AdBlock: ||domain.com^
        if (trimmed.startsWith("||") && trimmed.contains("^")) {
            val domain = trimmed
                .removePrefix("||")
                .substringBefore("^")
                .substringBefore("$")
            if (isValidDomain(domain)) {
                return domain
            }
            return null
        }

        // Format simple domaine
        if (!trimmed.contains(" ") &&
            !trimmed.contains("/") &&
            isValidDomain(trimmed)) {
            return trimmed
        }

        return null
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain == "localhost" || domain == "local") return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        if (domain.startsWith("-") || domain.endsWith("-")) return false
        if (!domain.contains(".")) return false

        // Regex simple pour valider un domaine
        val regex = Regex("^[a-z0-9]([a-z0-9\\-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9\\-]*[a-z0-9])?)+$")
        return regex.matches(domain)
    }

    private suspend fun saveList(
        name: String,
        url: String?,
        type: String,
        domains: List<String>
    ): Long {
        // Créer la liste
        val customList = CustomList(
            name = name,
            url = url,
            type = type,
            enabled = true,
            domainCount = domains.size,
            lastUpdated = System.currentTimeMillis()
        )

        val listId = database.customListDao().insertList(customList)

        // Sauvegarder les domaines par batch de 500
        val customDomains = domains.map { domain ->
            CustomDomain(domain = domain, listId = listId)
        }

        customDomains.chunked(500).forEach { chunk ->
            database.customListDao().insertDomains(chunk)
        }

        return listId
    }

    /**
     * Mettre à jour une liste existante
     */
    suspend fun updateList(list: CustomList): ImportResult {
        if (list.url.isNullOrEmpty()) {
            return ImportResult.Error("Cette liste n'a pas d'URL de mise à jour")
        }

        // Supprimer les anciens domaines
        database.customListDao().deleteDomainsForList(list.id)

        // Réimporter
        val result = importFromUrl(list.name, list.url, list.type)

        if (result is ImportResult.Success) {
            // Mettre à jour les métadonnées
            database.customListDao().updateList(
                list.copy(
                    domainCount = result.domainCount,
                    lastUpdated = System.currentTimeMillis()
                )
            )
        }

        return result
    }

    sealed class ImportResult {
        data class Success(val domainCount: Int, val listId: Long) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    data class PopularList(
        val name: String,
        val url: String,
        val type: String,
        val description: String
    )
}