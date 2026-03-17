package fr.bonobo.dnsphere.lists

import android.content.Context
import android.util.Log
import fr.bonobo.dnsphere.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ListDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ListDownloader"
        private const val CONNECT_TIMEOUT = 30_000  // 30 secondes
        private const val READ_TIMEOUT = 60_000     // 60 secondes
        private const val MAX_FILE_SIZE = 50 * 1024 * 1024  // 50 MB max
    }

    private val database = AppDatabase.getInstance(context)
    private val dao = database.externalListDao()

    /**
     * Met à jour une liste spécifique
     */
    suspend fun updateList(list: ExternalList): Result<Int> {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Téléchargement de ${list.name}...")

                // Télécharger le contenu
                val content = downloadContent(list.url)

                // Parser selon le format
                val domains = ListParser.parse(content, list.format)

                if (domains.isEmpty()) {
                    dao.setListError(list.id, "Aucun domaine trouvé")
                    return@withContext Result.failure(Exception("Aucun domaine trouvé"))
                }

                // Effacer les anciens domaines
                dao.clearDomainsForList(list.id)

                // Insérer les nouveaux domaines par lots
                val externalDomains = domains.map { domain ->
                    ExternalListDomain(listId = list.id, domain = domain)
                }

                // Insertion par lots de 1000
                externalDomains.chunked(1000).forEach { batch ->
                    dao.insertDomains(batch)
                }

                // Mettre à jour les stats
                dao.updateListStats(list.id, domains.size, System.currentTimeMillis())

                Log.d(TAG, "${list.name}: ${domains.size} domaines importés")
                Result.success(domains.size)

            } catch (e: Exception) {
                Log.e(TAG, "Erreur pour ${list.name}: ${e.message}")
                dao.setListError(list.id, e.message ?: "Erreur inconnue")
                Result.failure(e)
            }
        }
    }

    /**
     * Met à jour toutes les listes activées
     */
    suspend fun updateAllEnabledLists(): Map<String, Result<Int>> {
        val results = mutableMapOf<String, Result<Int>>()

        val lists = dao.getEnabledLists()
        lists.forEach { list ->
            results[list.name] = updateList(list)
        }

        return results
    }

    /**
     * Télécharge le contenu d'une URL
     */
    private fun downloadContent(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                requestMethod = "GET"
                setRequestProperty("User-Agent", "DNSphere/1.0")
                setRequestProperty("Accept", "text/plain, */*")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("HTTP $responseCode: ${connection.responseMessage}")
            }

            // Vérifier la taille
            val contentLength = connection.contentLength
            if (contentLength > MAX_FILE_SIZE) {
                throw Exception("Fichier trop volumineux (${contentLength / 1024 / 1024} MB)")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }

        } finally {
            connection.disconnect()
        }
    }

    /**
     * Ajoute une liste personnalisée
     */
    suspend fun addCustomList(
        name: String,
        url: String,
        description: String = "",
        category: ListCategory = ListCategory.CUSTOM
    ): Result<ExternalList> {
        return withContext(Dispatchers.IO) {
            try {
                // Vérifier si l'URL existe déjà
                val existing = dao.getListByUrl(url)
                if (existing != null) {
                    return@withContext Result.failure(Exception("Cette liste existe déjà"))
                }

                // Télécharger pour détecter le format
                val content = downloadContent(url)
                val format = ListParser.detectFormat(content)
                val domains = ListParser.parse(content, format)

                if (domains.isEmpty()) {
                    return@withContext Result.failure(Exception("Aucun domaine valide trouvé"))
                }

                // Créer la liste
                val list = ExternalList(
                    name = name,
                    url = url,
                    description = description,
                    category = category,
                    format = format,
                    enabled = true,
                    isBuiltIn = false
                )

                val id = dao.insertList(list).toInt()
                val insertedList = list.copy(id = id)

                // Insérer les domaines
                val externalDomains = domains.map { domain ->
                    ExternalListDomain(listId = id, domain = domain)
                }
                externalDomains.chunked(1000).forEach { batch ->
                    dao.insertDomains(batch)
                }

                dao.updateListStats(id, domains.size, System.currentTimeMillis())

                Result.success(insertedList)

            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}