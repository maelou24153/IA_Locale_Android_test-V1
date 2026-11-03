package com.iastudio.vm

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

enum class ChatMode(val label: String, val system: String, val maxTokens: Int, val hello: String) {
    ASSISTANT(
        "Assistant",
        "Tu es un assistant utile, chaleureux et honnête. Réponds en français, de façon claire et concise. Si tu ne sais pas, dis-le.",
        768, "Comment puis-je t'aider ?",
    ),
    CODE(
        "Code",
        "Tu es un expert en programmation. Réponds en français. Donne du code complet, correct et commenté " +
            "dans des blocs ```langage, puis explique brièvement les choix. Si l'utilisateur colle du code, " +
            "corrige-le ou améliore-le en montrant seulement ce qui change quand c'est possible. " +
            "Signale les erreurs et les cas limites.",
        1500, "Que veux-tu construire ou corriger ?",
    ),
    PROMPT_IMAGE(
        "Prompt image",
        "Tu transformes la demande de l'utilisateur en un prompt anglais détaillé pour Stable Diffusion : " +
            "sujet, décor, style, éclairage, qualité, séparés par des virgules. Réponds uniquement avec le prompt.",
        200, "Décris l'image que tu imagines.",
    ),
}

class ProjFile(val path: String, val role: String, status: String = "en attente") {
    var status by mutableStateOf(status)
}

private const val PLAN_SYSTEM =
    "Tu es un architecte logiciel senior. À partir de la demande, liste les fichiers nécessaires à un projet complet " +
        "et fonctionnel : point d'entrée, code, configuration, et un README avec les instructions pour lancer le logiciel. " +
        "Réponds UNIQUEMENT par une liste, un fichier par ligne, au format : chemin/fichier.ext | rôle en une phrase. " +
        "Maximum 12 fichiers. Choisis la technologie la plus simple qui répond à la demande " +
        "(par défaut : une page web HTML/CSS/JS qui fonctionne en ouvrant index.html, ou un script Python)."

private const val FILE_SYSTEM =
    "Tu es un développeur expert. Tu écris un fichier complet, correct et prêt à l'emploi, cohérent avec les autres " +
        "fichiers du projet (mêmes noms de fonctions, d'identifiants, d'imports et de fichiers). Aucun texte avant ou " +
        "après : uniquement le contenu du fichier dans un seul bloc de code. Jamais de « ... » ni de TODO : tout doit être écrit."

private const val REVIEW_SYSTEM =
    "Tu es un relecteur de code strict. Corrige les erreurs de syntaxe, les imports manquants, les noms incohérents " +
        "et les bugs évidents. Renvoie le fichier COMPLET corrigé dans un seul bloc de code, sans commentaire autour."

private const val SELECT_SYSTEM =
    "Tu es un lead développeur. Selon la demande de modification, liste les chemins des fichiers à modifier ou à créer, " +
        "un par ligne, sans aucun autre texte."

private const val MODIFY_SYSTEM =
    "Tu es un développeur expert. Applique la demande au fichier et renvoie le fichier COMPLET modifié dans un seul " +
        "bloc de code, sans autre texte."

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val vmm = VmManager(app)
    private val prefs = app.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    val catalog = Catalog.load(app)

    val ramGb: Double = run {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / 1e9
    }

    fun ramAdvice(): String = when {
        ramGb < 4.5 -> "modèles de 0,5 à 1,5 milliard de paramètres"
        ramGb < 7 -> "modèles jusqu'à 3-4 milliards de paramètres"
        ramGb < 10 -> "modèles jusqu'à 4 milliards, 7B possible mais lent"
        else -> "modèles jusqu'à 7 milliards de paramètres"
    }

    val machines = mutableStateListOf<String>()
    var current by mutableStateOf<String?>(null)
    var installed by mutableStateOf<Set<String>>(emptySet())
    val progress = mutableStateMapOf<String, Int>()
    var status by mutableStateOf("")
    var engines by mutableStateOf(vmm.engineStatus())

    // Réglages
    var temperature by mutableStateOf(0.6f)
    var ctxSize by mutableStateOf(8192)
    var threads by mutableStateOf((Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8))
    var autoReview by mutableStateOf(false)
    var hfToken by mutableStateOf(prefs.getString("hf_token", "") ?: "")

    fun saveToken(t: String) { hfToken = t; prefs.edit().putString("hf_token", t).apply() }

    /** Catalogue + modèles installés par lien ou via Hugging Face. */
    val allModels: List<ModelEntry>
        get() = catalog + installed.filter { f -> catalog.none { it.file == f } }.map { f ->
            if (f.startsWith("img__")) {
                val fast = f.contains("turbo", true) || f.contains("lcm", true) || f.contains("sdxs", true)
                ModelEntry("custom-$f", "image", f.removePrefix("img__"), f, "", if (fast) 4 else 20, if (fast) 1.0 else 7.0)
            } else ModelEntry("custom-$f", "chat", f, f, "")
        }

    // Chat
    val messages = mutableStateListOf<Pair<String, String>>()
    var mode by mutableStateOf(ChatMode.ASSISTANT)
    var chatModel by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    private var chatCtx = 0
    private var job: Job? = null
    private var stopped = false

    // Hugging Face
    var hfType by mutableStateOf("chat")
    var hfQuery by mutableStateOf("")
    val hfResults = mutableStateListOf<String>()
    var hfModel by mutableStateOf<String?>(null)
    val hfFiles = mutableStateListOf<Pair<String, Long>>()
    var hfLoading by mutableStateOf(false)

    // Projets de code
    val projects = mutableStateListOf<String>()
    var projectName by mutableStateOf("")
    var projLog by mutableStateOf("")
    val projFiles = mutableStateListOf<ProjFile>()
    var previewPath by mutableStateOf<String?>(null)
    var previewText by mutableStateOf("")

    // Images
    var imageLog by mutableStateOf("")
    var image by mutableStateOf<Bitmap?>(null)
    var imageFile by mutableStateOf<File?>(null)
    var imageSize by mutableStateOf(512)
    var negative by mutableStateOf("blurry, low quality, deformed, watermark")
    var strength by mutableStateOf(0.6f)
    var baseThumb by mutableStateOf<Bitmap?>(null)
    val gallery = mutableStateListOf<File>()

    init { refresh() }

    private fun srcFile(vm: String) = File(vmm.outDir(vm), "_src.png")

    private fun refresh() {
        machines.clear(); machines.addAll(vmm.list())
        if (current == null || current !in machines) current = machines.firstOrNull()
        installed = current?.let { vmm.installed(it) } ?: emptySet()
        gallery.clear(); current?.let { gallery.addAll(vmm.gallery(it)) }
        projects.clear(); current?.let { projects.addAll(vmm.projects(it)) }
        projFiles.clear(); previewPath = null; previewText = ""
        baseThumb = null
    }

    fun createMachine(name: String) {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
        if (clean.isEmpty()) return
        vmm.create(clean); current = clean; refresh()
    }

    fun select(name: String) { vmm.stopChat(); current = name; chatModel = null; refresh() }
    fun deleteMachine(name: String) { vmm.delete(name); current = null; refresh() }

    fun install(m: ModelEntry, customUrl: String? = null) {
        val vm = current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                progress[m.id] = 0
                Downloader.download(customUrl ?: m.url, File(vmm.modelsDir(vm), m.file), hfToken) { progress[m.id] = it }
                status = "${m.name} installé"
            } catch (e: Exception) {
                status = "Erreur : ${e.message}"
            } finally {
                progress.remove(m.id)
                withContext(Dispatchers.Main) { installed = vmm.installed(vm) }
            }
        }
    }

    fun remove(m: ModelEntry) {
        val vm = current ?: return
        File(vmm.modelsDir(vm), m.file).delete()
        installed = vmm.installed(vm)
    }

    // ---------- Hugging Face ----------

    fun hfSearch() {
        if (hfQuery.isBlank()) return
        hfLoading = true; hfModel = null; hfFiles.clear()
        viewModelScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { HfBrowser.search(hfQuery, hfToken) }
                hfResults.clear(); hfResults.addAll(r)
                if (r.isEmpty()) status = "Aucun résultat"
            } catch (e: Exception) { status = "Erreur : ${e.message}" }
            finally { hfLoading = false }
        }
    }

    fun hfOpen(id: String) {
        hfLoading = true; hfModel = id; hfFiles.clear()
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { HfBrowser.files(id, hfToken) }
                hfFiles.addAll(f)
                if (f.isEmpty()) status = "Aucun fichier .gguf simple dans ce dépôt"
            } catch (e: Exception) { status = "Erreur : ${e.message}" }
            finally { hfLoading = false }
        }
    }

    fun hfInstall(id: String, file: String) {
        val flat = file.substringAfterLast('/')
        val stored = if (hfType == "image") "img__$flat" else flat
        install(ModelEntry("hf-$id/$file", hfType, flat, stored, ""), HfBrowser.url(id, file))
    }

    // ---------- Moteur de chat ----------

    private suspend fun ensureChat(vm: String, model: ModelEntry) {
        if (vmm.chatRunning() && chatModel == model.id && chatCtx == ctxSize) return
        status = "Démarrage du moteur… (30 s à 2 min la première fois)"
        vmm.startChat(vm, File(vmm.modelsDir(vm), model.file), threads, ctxSize)
        chatModel = model.id; chatCtx = ctxSize; status = ""
    }

    fun stopWork() {
        stopped = true
        job?.cancel()
        vmm.stopChat(); chatModel = null
        busy = false; status = "Arrêté"
    }

    fun send(text: String, model: ModelEntry) {
        val vm = current ?: return
        if (text.isBlank() || busy) return
        messages.add("user" to text)
        busy = true; stopped = false
        job = viewModelScope.launch {
            try {
                ensureChat(vm, model)
                messages.add("assistant" to vmm.chat(messages.toList(), mode.system, mode.maxTokens, temperature.toDouble()))
            } catch (e: Exception) {
                if (!stopped) messages.add("assistant" to "⚠ ${e.message}")
                status = ""
            } finally { busy = false }
        }
    }

    fun clearChat() = messages.clear()
    fun changeMode(m: ChatMode) { mode = m; messages.clear() }

    // ---------- Génération de logiciels complets ----------

    private fun slug(s: String) = s.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifEmpty { "projet" }

    private fun extractCode(raw: String): String {
        val s = raw.indexOf("```")
        if (s < 0) return raw.trim()
        val nl = raw.indexOf('\n', s)
        if (nl < 0) return raw.trim()
        val e = raw.lastIndexOf("```")
        return raw.substring(nl + 1, if (e > nl) e else raw.length).trimEnd()
    }

    private fun cleanPath(line: String): String =
        line.trim().trimStart('-', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim('`', ' ', '*')

    private fun parsePlan(txt: String): List<ProjFile> {
        val out = LinkedHashMap<String, ProjFile>()
        txt.lines().forEach { l ->
            val parts = l.split("|", limit = 2)
            val path = cleanPath(parts[0])
            val ok = path.isNotEmpty() && path.length < 80 && !path.contains(' ') && path.contains('.')
            if (ok && path !in out && out.size < 12) out[path] = ProjFile(path, parts.getOrElse(1) { "" }.trim())
        }
        return out.values.toList()
    }

    fun buildProject(name: String, request: String, model: ModelEntry) {
        val vm = current ?: return
        if (request.isBlank() || busy) return
        busy = true; stopped = false
        projectName = slug(name.ifBlank { request.take(24) })
        job = viewModelScope.launch {
            try {
                ensureChat(vm, model)
                val dir = vmm.projectDir(vm, projectName)
                dir.deleteRecursively(); dir.mkdirs()
                projFiles.clear(); previewPath = null
                projLog = "1/3 · L'IA prépare le plan du projet…"
                val plan = parsePlan(vmm.chat(listOf("user" to "Demande : $request"), PLAN_SYSTEM, 600, 0.3))
                if (plan.isEmpty()) throw RuntimeException("Plan illisible. Reformule la demande ou choisis un modèle plus grand.")
                projFiles.addAll(plan)
                val summary = plan.joinToString("\n") { "- ${it.path} : ${it.role}" }
                plan.forEachIndexed { i, pf ->
                    projLog = "2/3 · Écriture de ${pf.path} (${i + 1}/${plan.size})"
                    pf.status = "écriture…"
                    val user = "Projet : $request\n\nFichiers du projet :\n$summary\n\n" +
                        "Écris le contenu COMPLET du fichier ${pf.path} (${pf.role}). Un seul bloc de code, rien d'autre."
                    var code = extractCode(vmm.chat(listOf("user" to user), FILE_SYSTEM, 2200, 0.3))
                    if (autoReview && code.isNotBlank()) {
                        pf.status = "relecture…"
                        projLog = "3/3 · Relecture de ${pf.path}"
                        val r = extractCode(vmm.chat(
                            listOf("user" to "Fichier ${pf.path} du projet « $request » :\n```\n$code\n```\nCorrige-le."),
                            REVIEW_SYSTEM, 2200, 0.2,
                        ))
                        if (r.isNotBlank()) code = r
                    }
                    vmm.writeProjectFile(dir, pf.path, code)
                    pf.status = "✓"
                }
                projLog = "Terminé : ${plan.size} fichiers. Appuie sur « Télécharger le .zip »."
                projects.clear(); projects.addAll(vmm.projects(vm))
            } catch (e: Exception) {
                if (!stopped) projLog = "⚠ ${e.message}"
            } finally { busy = false }
        }
    }

    fun modifyProject(request: String, model: ModelEntry) {
        val vm = current ?: return
        if (request.isBlank() || busy || projectName.isEmpty()) return
        busy = true; stopped = false
        job = viewModelScope.launch {
            try {
                ensureChat(vm, model)
                val dir = vmm.projectDir(vm, projectName)
                val existing = vmm.projectFiles(dir)
                projLog = "L'IA cherche les fichiers à modifier…"
                val sel = vmm.chat(
                    listOf("user" to "Fichiers du projet :\n${existing.joinToString("\n")}\n\nDemande : $request"),
                    SELECT_SYSTEM, 300, 0.2,
                ).lines().map { cleanPath(it.substringBefore('|')) }
                    .filter { it.isNotEmpty() && it.contains('.') && !it.contains(' ') }.distinct().take(8)
                if (sel.isEmpty()) throw RuntimeException("L'IA n'a désigné aucun fichier. Reformule la demande.")
                sel.forEachIndexed { i, path ->
                    projLog = "Modification de $path (${i + 1}/${sel.size})"
                    val pf = projFiles.firstOrNull { it.path == path } ?: ProjFile(path, "").also { projFiles.add(it) }
                    pf.status = "modification…"
                    val old = vmm.readProjectFile(dir, path)?.take(9000) ?: "(nouveau fichier)"
                    val user = "Fichiers du projet :\n${existing.joinToString("\n")}\n\nDemande : $request\n\n" +
                        "Contenu actuel de $path :\n```\n$old\n```\nRenvoie le fichier $path complet et modifié."
                    val code = extractCode(vmm.chat(listOf("user" to user), MODIFY_SYSTEM, 2200, 0.3))
                    if (code.isNotBlank()) vmm.writeProjectFile(dir, path, code)
                    pf.status = "✓ modifié"
                }
                projLog = "Modifications terminées. Retélécharge le .zip."
            } catch (e: Exception) {
                if (!stopped) projLog = "⚠ ${e.message}"
            } finally { busy = false }
        }
    }

    fun openProject(slug: String) {
        val vm = current ?: return
        projectName = slug
        projFiles.clear()
        vmm.projectFiles(vmm.projectDir(vm, slug)).forEach { projFiles.add(ProjFile(it, "", "✓")) }
        previewPath = null; previewText = ""
        projLog = "Projet « $slug » ouvert"
    }

    fun preview(path: String) {
        val vm = current ?: return
        previewPath = path
        previewText = vmm.readProjectFile(vmm.projectDir(vm, projectName), path) ?: "(fichier absent)"
    }

    fun deleteProject(slug: String) {
        val vm = current ?: return
        vmm.projectDir(vm, slug).deleteRecursively()
        projects.remove(slug)
        if (projectName == slug) { projectName = ""; projFiles.clear(); previewPath = null }
    }

    fun exportProject() {
        val vm = current ?: return
        if (projectName.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            projLog = try { "Archive enregistrée : " + vmm.exportZip(vmm.projectDir(vm, projectName)) }
            catch (e: Exception) { "⚠ ${e.message}" }
        }
    }

    // ---------- Images : création et modification ----------

    private fun decodeSampled(open: () -> InputStream?, max: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, b) }
        var s = 1
        while (b.outWidth / s > max * 2 || b.outHeight / s > max * 2) s *= 2
        val o = BitmapFactory.Options().apply { inSampleSize = s }
        return open()?.use { BitmapFactory.decodeStream(it, null, o) }
    }

    private fun saveSource(bmp: Bitmap) {
        val vm = current ?: return
        FileOutputStream(srcFile(vm)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        baseThumb = bmp
    }

    fun setBase(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bmp = decodeSampled({ getApplication<Application>().contentResolver.openInputStream(uri) }, 1024)
            if (bmp == null) { imageLog = "⚠ Impossible de lire cette image"; return@launch }
            saveSource(bmp)
        }
    }

    fun setBaseFromFile(f: File) {
        viewModelScope.launch(Dispatchers.IO) {
            decodeSampled({ f.inputStream() }, 1024)?.let { saveSource(it) }
        }
    }

    fun clearBase() {
        current?.let { srcFile(it).delete() }
        baseThumb = null
    }

    private fun prepareBase(vm: String, size: Int): File? {
        if (baseThumb == null) return null
        val bmp = BitmapFactory.decodeFile(srcFile(vm).path) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val sq = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(sq, size, size, true)
        val dest = File(vmm.outDir(vm), "_base.png")
        FileOutputStream(dest).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return dest
    }

    fun generate(prompt: String, model: ModelEntry) {
        val vm = current ?: return
        if (prompt.isBlank() || busy) return
        busy = true; stopped = false; imageLog = "Génération en cours…"
        job = viewModelScope.launch {
            try {
                vmm.stopChat() // libère la RAM pour le moteur d'images
                chatModel = null
                val base = withContext(Dispatchers.IO) { prepareBase(vm, imageSize) }
                val f = vmm.generateImage(vm, model, prompt, negative, imageSize, threads, base, strength) { imageLog = it }
                showImage(f)
                gallery.add(0, f)
                imageLog = "Terminé : ${f.name}"
            } catch (e: Exception) {
                if (!stopped) imageLog = "⚠ ${e.message}"
            } finally { busy = false }
        }
    }

    fun showImage(f: File) {
        viewModelScope.launch {
            image = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path) }
            imageFile = f
        }
    }

    fun saveToPhotos(f: File) {
        viewModelScope.launch(Dispatchers.IO) {
            imageLog = if (vmm.exportToPhotos(f)) "Enregistrée dans Photos › IA Studio VM"
            else "Enregistrement impossible (Android 10 ou plus requis)"
        }
    }

    fun deleteImage(f: File) {
        f.delete(); gallery.remove(f)
        if (imageFile == f) { image = null; imageFile = null }
    }

    override fun onCleared() { vmm.stopChat() }
}
