package com.iastudio.vm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Root(vm) }
            }
        }
    }
}

@Composable
fun Root(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Storage, null) }, label = { Text("Machines") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Chat, null) }, label = { Text("Chat") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Code, null) }, label = { Text("Projet") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Default.Image, null) }, label = { Text("Images") })
                NavigationBarItem(tab == 4, { tab = 4 }, { Icon(Icons.Default.Help, null) }, label = { Text("Aide") })
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).padding(horizontal = 16.dp, vertical = 12.dp).fillMaxSize()) {
            when (tab) {
                0 -> MachinesScreen(vm)
                1 -> ChatScreen(vm)
                2 -> ProjectScreen(vm)
                3 -> ImageScreen(vm)
                else -> HelpScreen()
            }
        }
    }
}

// ---------------------------------------------------------------- Chat & code

@Composable
fun RichText(content: String) {
    val clip = LocalClipboardManager.current
    val parts = content.split("```")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, p ->
            if (i % 2 == 0) {
                if (p.isNotBlank()) Text(p.trim())
            } else {
                val lang = p.substringBefore('\n', "").trim()
                val code = p.substringAfter('\n', p).trimEnd()
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(lang.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton({ clip.setText(AnnotatedString(code)) }) { Text("Copier") }
                        }
                        Text(
                            code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ModelPicker(models: List<ModelEntry>, selected: ModelEntry, onPick: (ModelEntry) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text(selected.name.take(38)) }
        DropdownMenu(open, { open = false }) {
            models.forEach { DropdownMenuItem({ Text(it.name) }, { onPick(it); open = false }) }
        }
    }
}

// ---------------------------------------------------------------- Images

@Composable
fun Thumb(f: File, size: Dp, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 4 })
        }
    }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
    ) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

@Composable
fun ImageScreen(vm: AppViewModel) {
    val models = vm.allModels.filter { it.type == "image" && it.file in vm.installed }
    var sel by remember { mutableStateOf<ModelEntry?>(null) }
    val model = sel?.takeIf { it in models } ?: models.firstOrNull()
    var prompt by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) vm.setBase(uri) }
    if (model == null) { Text("Installe d'abord une IA d'images dans l'onglet Machines."); return }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (vm.baseThumb == null) "Créer une image" else "Modifier une image", style = MaterialTheme.typography.titleLarge)
        ModelPicker(models, model) { sel = it }

        // Image de départ (modification)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ picker.launch("image/*") }) { Text(if (vm.baseThumb == null) "Choisir une photo à modifier" else "Changer de photo") }
            if (vm.baseThumb != null) TextButton({ vm.clearBase() }) { Text("Retirer") }
        }
        vm.baseThumb?.let { b ->
            Image(b.asImageBitmap(), null, Modifier.height(140.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
            Text("Force de la modification : ${(vm.strength * 100).toInt()} %  (bas = proche de l'original)")
            Slider(vm.strength, { vm.strength = it }, valueRange = 0.2f..0.95f)
        }

        OutlinedTextField(
            prompt, { prompt = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            label = { Text(if (vm.baseThumb == null) "Décris l'image (anglais conseillé)" else "Décris le résultat voulu") },
        )
        OutlinedTextField(
            vm.negative, { vm.negative = it }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            label = { Text("À éviter (anglais)") }, singleLine = true,
        )
        Text("Taille : ${vm.imageSize}×${vm.imageSize}  (256 = rapide · 512 = qualité)")
        Slider(vm.imageSize / 64f, { vm.imageSize = (it.toInt() * 64).coerceIn(256, 768) }, valueRange = 4f..12f, steps = 7)
        Text("Threads CPU : ${vm.threads}")
        Slider(vm.threads.toFloat(), { vm.threads = it.toInt() }, valueRange = 1f..8f, steps = 6)
        Button({ vm.generate(prompt, model) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.baseThumb == null) "Générer" else "Transformer la photo")
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(vm.imageLog, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        vm.image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) }
        vm.imageFile?.let { f ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ vm.saveToPhotos(f) }) { Text("Enregistrer") }
                TextButton({ vm.setBaseFromFile(f) }) { Text("Modifier") }
                TextButton({ vm.deleteImage(f) }) { Text("Supprimer") }
            }
        }
        if (vm.gallery.isNotEmpty()) {
            Text("Galerie", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.gallery.toList()) { f -> Thumb(f, 92.dp) { vm.showImage(f) } }
            }
        }
    }
}

// ---------------------------------------------------------------- Aide (notice)

@Composable
fun HelpScreen() {
    val ctx = LocalContext.current
    val lines = remember { ctx.assets.open("NOTICE.md").bufferedReader().readLines() }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { l ->
            when {
                l.startsWith("# ") -> Text(l.drop(2), style = MaterialTheme.typography.headlineMedium)
                l.startsWith("## ") -> Text(l.drop(3), Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleLarge)
                l.startsWith("- ") -> Text("•  " + l.drop(2).replace("**", ""))
                l.isBlank() -> Spacer(Modifier.height(2.dp))
                else -> Text(l.replace("**", ""))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
