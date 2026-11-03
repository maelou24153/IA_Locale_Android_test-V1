package com.iastudio.vm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ModelEntry(
    val id: String,
    val type: String, // "chat" ou "image"
    val name: String,
    val file: String,
    val url: String,
    val steps: Int = 20,
    val cfg: Double = 7.0,
    val minRam: Int = 0,
)

object Catalog {
    fun load(ctx: Context): List<ModelEntry> {
        val text = ctx.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ModelEntry(
                o.getString("id"), o.getString("type"), o.getString("name"),
                o.getString("file"), o.getString("url"),
                o.optInt("steps", 20), o.optDouble("cfg", 7.0), o.optInt("minRam", 0),
            )
        }
    }
}

object Downloader {
    /** Télécharge avec reprise (fichier .part). onProgress reçoit un pourcentage 0..100. */
    fun download(url: String, dest: File, token: String, onProgress: (Int) -> Unit) {
        val part = File(dest.path + ".part")
        val already = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 30000
            if (already > 0) setRequestProperty("Range", "bytes=$already-")
            if (token.isNotBlank() && url.contains("huggingface.co")) setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        if (code == 401 || code == 403) throw RuntimeException("Accès refusé (HTTP $code) : modèle réservé, ajoute ton jeton Hugging Face")
        if (code !in 200..299) throw RuntimeException("Téléchargement refusé (HTTP $code)")
        val resumed = code == 206
        val total = conn.contentLengthLong.let { if (it > 0) it + (if (resumed) already else 0) else -1L }
        var done = if (resumed) already else 0L
        FileOutputStream(part, resumed).use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt()
                        if (p != last) { last = p; onProgress(p) }
                    }
                }
            }
        }
        if (!part.renameTo(dest)) throw RuntimeException("Impossible de finaliser ${dest.name}")
    }
}

/** Recherche dans Hugging Face : accès à tous les modèles GGUF publics. */
object HfBrowser {
    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        if (c.responseCode !in 200..299) throw RuntimeException("Hugging Face a répondu HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun search(query: String, token: String): List<String> {
        val q = URLEncoder.encode(query, "UTF-8")
        val arr = JSONArray(get("https://huggingface.co/api/models?search=$q&filter=gguf&sort=downloads&direction=-1&limit=25", token))
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
    }

    /** Fichiers .gguf d'un dépôt (sans les modèles découpés ni les fichiers mmproj), avec leur taille. */
    fun files(id: String, token: String): List<Pair<String, Long>> {
        val o = JSONObject(get("https://huggingface.co/api/models/$id?blobs=true", token))
        val s = o.getJSONArray("siblings")
        return (0 until s.length()).map { s.getJSONObject(it) }
            .filter { j ->
                val n = j.getString("rfilename")
                n.endsWith(".gguf") && !n.contains("mmproj") && !Regex("-0000\\d-of-0000\\d").containsMatchIn(n)
            }
            .map { it.getString("rfilename") to it.optLong("size", 0L) }
            .sortedBy { it.second }
    }

    fun url(id: String, file: String) = "https://huggingface.co/$id/resolve/main/$file"
}
