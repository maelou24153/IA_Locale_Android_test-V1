package com.iastudio.vm

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Une « machine virtuelle » = un conteneur isolé : son propre dossier de modèles, de sorties,
 * et (si libproot.so est présent) un système de fichiers Linux isolé lancé via proot.
 * Les moteurs (llama-server, sd) sont des exécutables natifs ARM64 fournis dans l'APK.
 */
class VmManager(private val ctx: Context) {
    private val base = File(ctx.filesDir, "vms").apply { mkdirs() }
    private val nativeDir = ctx.applicationInfo.nativeLibraryDir
    private var chatProc: Process? = null
    private val port = 8080

    fun list(): List<String> =
        base.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun create(name: String) {
        File(base, "$name/models").mkdirs()
        File(base, "$name/out").mkdirs()
        File(base, "$name/rootfs").mkdirs()
    }

    fun delete(name: String) { stopChat(); File(base, name).deleteRecursively() }
    fun modelsDir(vm: String) = File(base, "$vm/models").apply { mkdirs() }
    fun outDir(vm: String) = File(base, "$vm/out").apply { mkdirs() }
    fun installed(vm: String): Set<String> =
        modelsDir(vm).list()?.filter { !it.endsWith(".part") }?.toSet() ?: emptySet()

    /** Images générées (les fichiers commençant par « _ » sont des fichiers de travail). */
    fun gallery(vm: String): List<File> =
        outDir(vm).listFiles { f -> f.extension == "png" && !f.name.startsWith("_") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun engineStatus(): String {
        fun has(n: String) = File(nativeDir, n).exists()
        return "Chat : ${if (has("libllama_server.so")) "OK" else "absent"} · " +
            "Images : ${if (has("libsd.so")) "OK" else "absent"} · " +
            "proot : ${if (has("libproot.so")) "OK" else "absent (mode bac à sable)"}"
    }

    private fun wrap(vm: String, cmd: List<String>): List<String> {
        val proot = File(nativeDir, "libproot.so")
        val rootfs = File(base, "$vm/rootfs")
        if (!proot.exists() || !rootfs.exists()) return cmd
        val m = modelsDir(vm).path
        val o = outDir(vm).path
        return listOf(
            proot.path, "-r", rootfs.path, "-b", "/dev", "-b", "/proc",
            "-b", "$m:$m", "-b", "$o:$o", "-b", "$nativeDir:$nativeDir",
        ) + cmd
    }

    private fun builder(vm: String, cmd: List<String>): ProcessBuilder =
        ProcessBuilder(wrap(vm, cmd)).redirectErrorStream(true).apply {
            environment()["PROOT_TMP_DIR"] = ctx.cacheDir.path
            environment()["TMPDIR"] = ctx.cacheDir.path
        }

    // ---------- Chat (llama.cpp) ----------

    suspend fun startChat(vm: String, model: File, threads: Int, ctxSize: Int) = withContext(Dispatchers.IO) {
        stopChat()
        val exe = File(nativeDir, "libllama_server.so")
        if (!exe.exists()) throw RuntimeException("Moteur de chat absent de l'APK (voir le workflow GitHub)")
        chatProc = builder(
            vm,
            listOf(
                exe.path, "-m", model.path, "--host", "127.0.0.1", "--port", "$port",
                "-c", "$ctxSize", "-t", "$threads",
            ),
        ).start()
        // Vide la sortie du moteur pour qu'il ne se bloque pas.
        val p = chatProc!!
        Thread { try { p.inputStream.bufferedReader().forEachLine { } } catch (_: Exception) {} }.start()
        repeat(240) {
            if (!p.isAlive) throw RuntimeException("Le moteur de chat s'est arrêté (mémoire insuffisante ? essaie un modèle plus petit)")
            try {
                val c = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
                c.connectTimeout = 500; c.readTimeout = 500
                if (c.responseCode == 200) return@withContext
            } catch (_: Exception) {}
            delay(500)
        }
        throw RuntimeException("Le moteur de chat ne répond pas")
    }

    fun chatRunning() = chatProc?.isAlive == true

    fun stopChat() {
        chatProc?.destroy(); chatProc = null
    }

    suspend fun chat(history: List<Pair<String, String>>, system: String, maxTokens: Int, temperature: Double = 0.6): String =
        withContext(Dispatchers.IO) {
            val msgs = JSONArray()
            msgs.put(JSONObject().put("role", "system").put("content", system))
            history.forEach { (r, c) -> msgs.put(JSONObject().put("role", r).put("content", c)) }
            val body = JSONObject().put("messages", msgs).put("temperature", temperature).put("max_tokens", maxTokens)
            val c = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 5000
            c.readTimeout = 600000
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val txt = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            if (c.responseCode !in 200..299) throw RuntimeException("Erreur moteur : $txt")
            JSONObject(txt).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        }

    // ---------- Images (stable-diffusion.cpp) : création et modification ----------

    suspend fun generateImage(
        vm: String, model: ModelEntry, prompt: String, negative: String, size: Int, threads: Int,
        baseImage: File?, strength: Float, onLog: (String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val exe = File(nativeDir, "libsd.so")
        if (!exe.exists()) throw RuntimeException("Moteur d'images absent de l'APK (voir le workflow GitHub)")
        val out = File(outDir(vm), "img_${System.currentTimeMillis()}.png")
        val cmd = mutableListOf(
            exe.path, "-m", File(modelsDir(vm), model.file).path, "-p", prompt, "-o", out.path,
            "--steps", "${model.steps}", "--cfg-scale", "${model.cfg}",
            "-W", "$size", "-H", "$size", "-t", "$threads", "--seed", "-1",
        )
        if (negative.isNotBlank() && model.cfg > 1.0) { cmd += listOf("-n", negative) }
        if (baseImage != null) {
            // Modification d'image : l'image de départ est transformée selon la description.
            cmd += listOf("-M", "img2img", "-i", baseImage.path, "--strength", "$strength")
        }
        val p = builder(vm, cmd).start()
        p.inputStream.bufferedReader().forEachLine { onLog(it.takeLast(120)) }
        val code = p.waitFor()
        if (code != 0 || !out.exists()) throw RuntimeException("Échec de la génération (code $code). Essaie une taille plus petite.")
        out
    }

    // ---------- Projets de code (logiciels générés) ----------

    fun projectDir(vm: String, slug: String) = File(base, "$vm/projects/$slug")

    fun projects(vm: String): List<String> =
        File(base, "$vm/projects").listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun projectFiles(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()

    fun writeProjectFile(dir: File, path: String, content: String) {
        val clean = path.trim().trimStart('/', '\\').replace("\\", "/")
        if (clean.isEmpty() || clean.split("/").any { it == ".." }) throw RuntimeException("Chemin refusé : $path")
        val f = File(dir, clean)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    fun readProjectFile(dir: File, path: String): String? =
        File(dir, path).takeIf { it.isFile }?.readText()

    fun zipProject(dir: File): File {
        val zip = File(ctx.cacheDir, "${dir.name}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            projectFiles(dir).forEach { rel ->
                z.putNextEntry(ZipEntry(rel))
                File(dir, rel).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return zip
    }

    /** Enregistre le .zip dans Téléchargements (Android 10+) et renvoie où le trouver. */
    fun exportZip(dir: File): String {
        val zip = zipProject(dir)
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/IA Studio VM")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                ?: throw RuntimeException("Export impossible")
            ctx.contentResolver.openOutputStream(uri)?.use { o -> zip.inputStream().use { it.copyTo(o) } }
            return "Téléchargements › IA Studio VM › ${zip.name}"
        }
        val d = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val dest = File(d, zip.name)
        zip.copyTo(dest, true)
        return dest.path
    }

    /** Copie l'image dans la galerie Photos du téléphone (Android 10+). */
    fun exportToPhotos(f: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IA Studio VM")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return true
    }
}
