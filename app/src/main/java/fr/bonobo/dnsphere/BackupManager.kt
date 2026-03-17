package fr.bonobo.dnsphere

import android.content.Context
import android.net.Uri
import android.util.Log
import fr.bonobo.dnsphere.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde et restauration des listes de blocage personnalisées.
 *
 * Contenu du ZIP :
 *  - metadata.json        → version, date, app
 *  - whitelist.json       → domaines jamais bloqués
 *  - custom_lists.json    → listes custom + leurs domaines
 *  - external_lists.json  → listes externes avec URL + TOUS les domaines (offline)
 *  - settings.json        → préférences vpn_prefs
 */
class BackupManager(private val context: Context) {

    companion object {
        private const val TAG            = "BackupManager"
        private const val BACKUP_VERSION = 2  // v2 : domaines externes inclus

        const val FILE_WHITELIST      = "whitelist.json"
        const val FILE_CUSTOM_LISTS   = "custom_lists.json"
        const val FILE_EXTERNAL_LISTS = "external_lists.json"
        const val FILE_SETTINGS       = "settings.json"
        const val FILE_METADATA       = "metadata.json"
    }

    private val database = AppDatabase.getInstance(context)

    // =========================================================================
    // EXPORT
    // =========================================================================

    suspend fun exportBackup(): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val timestamp  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName   = "dnsphere_backup_$timestamp.zip"
            val outputFile = File(context.cacheDir, fileName)

            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zip ->
                zip.addEntry(FILE_METADATA,      buildMetadata())
                zip.addEntry(FILE_WHITELIST,      exportWhitelist())
                zip.addEntry(FILE_CUSTOM_LISTS,   exportCustomLists())
                zip.addEntry(FILE_EXTERNAL_LISTS, exportExternalLists())  // ← inclut les domaines
                zip.addEntry(FILE_SETTINGS,       exportSettings())
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outputFile
            )

            val sizeKb = outputFile.length() / 1024
            Log.d(TAG, "✅ Backup créé: $fileName ($sizeKb KB)")
            Result.success(uri)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Export failed", e)
            Result.failure(e)
        }
    }

    private fun ZipOutputStream.addEntry(name: String, json: String) {
        putNextEntry(ZipEntry(name))
        write(json.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun buildMetadata(): String {
        val stats = database.externalListDao()
        return JSONObject().apply {
            put("version", BACKUP_VERSION)
            put("date",    System.currentTimeMillis())
            put("app",     "DNSphere")
            put("package", context.packageName)
        }.toString(2)
    }

    private suspend fun exportWhitelist(): String {
        val items = database.whitelistDao().getAllSync()
        val array = JSONArray()
        items.forEach { array.put(it.domain) }
        return JSONObject().apply { put("domains", array) }.toString(2)
    }

    private suspend fun exportCustomLists(): String {
        val lists = database.customListDao().getEnabledLists()
        val array = JSONArray()
        lists.forEach { list ->
            array.put(JSONObject().apply {
                put("name",    list.name)
                put("enabled", list.enabled)
                val domains = database.customListDao().getDomainEntitiesForList(list.id)
                val domArr  = JSONArray()
                domains.forEach { domArr.put(it.domain) }
                put("domains", domArr)
            })
        }
        return JSONObject().apply { put("lists", array) }.toString(2)
    }

    /**
     * Export complet : URL + format + TOUS les domaines pour restauration offline.
     */
    private suspend fun exportExternalLists(): String {
        val lists = database.externalListDao().getAllListsSync()
        val array = JSONArray()
        lists.forEach { list ->
            array.put(JSONObject().apply {
                put("name",        list.name)
                put("url",         list.url)
                put("description", list.description ?: "")
                put("category",    list.category.name)
                put("format",      list.format.name)
                put("enabled",     list.enabled)
                put("isBuiltIn",   list.isBuiltIn)
                put("domainCount", list.domainCount)
                put("lastUpdated", list.lastUpdated)

                // ← NOUVEAU : inclure tous les domaines pour restauration offline
                val domains = database.externalListDao().getDomainsForList(list.id)
                val domArr  = JSONArray()
                domains.forEach { domArr.put(it.domain) }
                put("domains", domArr)
            })
        }
        return JSONObject().apply { put("lists", array) }.toString(2)
    }

    private fun exportSettings(): String {
        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        return JSONObject().apply {
            put("block_ads",      prefs.getBoolean("block_ads",      true))
            put("block_trackers", prefs.getBoolean("block_trackers", true))
            put("block_malware",  prefs.getBoolean("block_malware",  true))
            put("block_shopping", prefs.getBoolean("block_shopping", true))
            put("use_doh",        prefs.getBoolean("use_doh",        false))
            put("use_dot",        prefs.getBoolean("use_dot",        false))
            put("doh_provider",   prefs.getString("doh_provider",    "cloudflare"))
        }.toString(2)
    }

    // =========================================================================
    // IMPORT
    // =========================================================================

    data class ImportResult(
        val whitelistImported:      Int     = 0,
        val customListsImported:    Int     = 0,
        val customDomainsImported:  Int     = 0,
        val externalListsImported:  Int     = 0,
        val externalDomainsRestored:Int     = 0,
        val settingsRestored:       Boolean = false,
        val errors:                 List<String> = emptyList()
    ) {
        override fun toString() =
            "Whitelist: $whitelistImported • Custom: $customListsImported listes ($customDomainsImported dom) • " +
                    "Externes: $externalListsImported listes ($externalDomainsRestored dom)"
    }

    /**
     * @param mergeMode true = ajouter sans écraser / false = remplacer tout
     * @param restoreDomainsOffline true = restaurer les domaines depuis le ZIP sans re-télécharger
     */
    suspend fun importBackup(
        uri: Uri,
        mergeMode: Boolean = true,
        restoreDomainsOffline: Boolean = true
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val entries = readZip(uri)

            // Vérifier la version
            entries[FILE_METADATA]?.let { meta ->
                val version = JSONObject(meta).optInt("version", 0)
                if (version > BACKUP_VERSION) {
                    return@withContext Result.failure(
                        Exception("Sauvegarde trop récente (v$version), mettez l'app à jour")
                    )
                }
            }

            var whitelistCount     = 0
            var customListCount    = 0
            var customDomainCount  = 0
            var externalCount      = 0
            var externalDomCount   = 0
            var settingsOk         = false
            val errors             = mutableListOf<String>()

            entries[FILE_WHITELIST]?.let { json ->
                try { whitelistCount = importWhitelist(json, mergeMode) }
                catch (e: Exception) { errors.add("Whitelist: ${e.message}") }
            }

            entries[FILE_CUSTOM_LISTS]?.let { json ->
                try {
                    val (l, d) = importCustomLists(json, mergeMode)
                    customListCount  = l
                    customDomainCount = d
                } catch (e: Exception) { errors.add("Custom: ${e.message}") }
            }

            entries[FILE_EXTERNAL_LISTS]?.let { json ->
                try {
                    val (l, d) = importExternalLists(json, mergeMode, restoreDomainsOffline)
                    externalCount  = l
                    externalDomCount = d
                } catch (e: Exception) { errors.add("Externes: ${e.message}") }
            }

            entries[FILE_SETTINGS]?.let { json ->
                try { importSettings(json); settingsOk = true }
                catch (e: Exception) { errors.add("Paramètres: ${e.message}") }
            }

            Result.success(ImportResult(
                whitelistImported       = whitelistCount,
                customListsImported     = customListCount,
                customDomainsImported   = customDomainCount,
                externalListsImported   = externalCount,
                externalDomainsRestored = externalDomCount,
                settingsRestored        = settingsOk,
                errors                  = errors
            ))

        } catch (e: Exception) {
            Log.e(TAG, "❌ Import failed", e)
            Result.failure(e)
        }
    }

    private fun readZip(uri: Uri): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(BufferedInputStream(stream)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        entries[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return entries
    }

    private suspend fun importWhitelist(json: String, merge: Boolean): Int {
        if (!merge) database.whitelistDao().deleteAll()
        val domains = JSONObject(json).getJSONArray("domains")
        var count = 0
        for (i in 0 until domains.length()) {
            val domain = domains.getString(i).trim().lowercase()
            if (domain.isNotEmpty()) {
                database.whitelistDao().insert(WhitelistItem(domain = domain))
                count++
            }
        }
        return count
    }

    private suspend fun importCustomLists(json: String, merge: Boolean): Pair<Int, Int> {
        if (!merge) database.customListDao().deleteAll()
        val lists = JSONObject(json).getJSONArray("lists")
        var listCount = 0; var domainCount = 0
        for (i in 0 until lists.length()) {
            val obj    = lists.getJSONObject(i)
            val listId = database.customListDao().insertList(
                CustomList(name = obj.getString("name"), enabled = obj.optBoolean("enabled", true))
            )
            listCount++
            val domains = obj.optJSONArray("domains")
            if (domains != null) {
                for (j in 0 until domains.length()) {
                    val d = domains.getString(j).trim().lowercase()
                    if (d.isNotEmpty()) {
                        database.customListDao().insertDomain(CustomListDomain(listId = listId, domain = d))
                        domainCount++
                    }
                }
            }
        }
        return Pair(listCount, domainCount)
    }

    private suspend fun importExternalLists(
        json: String,
        merge: Boolean,
        restoreDomainsOffline: Boolean
    ): Pair<Int, Int> {
        val lists     = JSONObject(json).getJSONArray("lists")
        var listCount = 0
        var domCount  = 0

        for (i in 0 until lists.length()) {
            val obj       = lists.getJSONObject(i)
            val isBuiltIn = obj.optBoolean("isBuiltIn", false)
            val url       = obj.optString("url", "")
            val enabled   = obj.optBoolean("enabled", true)

            if (isBuiltIn) {
                // Listes intégrées : restaurer uniquement l'état activé/désactivé
                if (url.isNotEmpty()) {
                    database.externalListDao().setEnabledByUrl(url, enabled)
                }
                continue
            }

            // Liste custom : importer si nouvelle (mode merge) ou toujours (mode replace)
            val alreadyExists = url.isNotEmpty() && database.externalListDao().existsByUrl(url)
            if (merge && alreadyExists) continue

            if (url.isNotEmpty()) {
                val format = try {
                    fr.bonobo.dnsphere.data.ListFormat.valueOf(obj.optString("format", "HOSTS"))
                } catch (e: Exception) { fr.bonobo.dnsphere.data.ListFormat.HOSTS }

                val category = try {
                    ListCategory.valueOf(obj.optString("category", "CUSTOM"))
                } catch (e: Exception) { ListCategory.CUSTOM }

                val listId = database.externalListDao().insertList(
                    ExternalList(
                        name        = obj.getString("name"),
                        url         = url,
                        description = obj.optString("description", ""),
                        category    = category,
                        format      = format,
                        enabled     = enabled,
                        isBuiltIn   = false,
                        domainCount = 0,
                        lastUpdated = obj.optLong("lastUpdated", 0)
                    )
                ).toInt()
                listCount++

                // ← NOUVEAU : restaurer les domaines depuis le ZIP (offline)
                if (restoreDomainsOffline) {
                    val domains = obj.optJSONArray("domains")
                    if (domains != null && domains.length() > 0) {
                        val batch = mutableListOf<ExternalListDomain>()
                        for (j in 0 until domains.length()) {
                            val d = domains.getString(j).trim().lowercase()
                            if (d.isNotEmpty()) batch.add(ExternalListDomain(listId = listId, domain = d))
                            // Insertion par lots de 1000
                            if (batch.size >= 1000) {
                                database.externalListDao().insertDomains(batch)
                                domCount += batch.size
                                batch.clear()
                            }
                        }
                        if (batch.isNotEmpty()) {
                            database.externalListDao().insertDomains(batch)
                            domCount += batch.size
                        }
                        // Mettre à jour le compteur de domaines
                        database.externalListDao().updateListStats(
                            listId, domains.length(), obj.optLong("lastUpdated", System.currentTimeMillis())
                        )
                    }
                }
            }
        }
        return Pair(listCount, domCount)
    }

    private fun importSettings(json: String) {
        val obj   = JSONObject(json)
        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (obj.has("block_ads"))      putBoolean("block_ads",      obj.getBoolean("block_ads"))
            if (obj.has("block_trackers")) putBoolean("block_trackers", obj.getBoolean("block_trackers"))
            if (obj.has("block_malware"))  putBoolean("block_malware",  obj.getBoolean("block_malware"))
            if (obj.has("block_shopping")) putBoolean("block_shopping", obj.getBoolean("block_shopping"))
            if (obj.has("use_doh"))        putBoolean("use_doh",        obj.getBoolean("use_doh"))
            if (obj.has("use_dot"))        putBoolean("use_dot",        obj.getBoolean("use_dot"))
            if (obj.has("doh_provider"))   putString("doh_provider",    obj.getString("doh_provider"))
            apply()
        }
    }
}