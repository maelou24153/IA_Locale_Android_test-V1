package com.iastudio.vm

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun MachinesScreen(vm: AppViewModel) {
    var newName by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("✻ IA Studio", style = MaterialTheme.typography.headlineMedium)
        Text(vm.engines, color = grey)
        Text("RAM détectée : ${"%.1f".format(vm.ramGb)} Go · conseillé : ${vm.ramAdvice()}", color = grey)

        Text("Machines virtuelles", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), label = { Text("Nom de la machine") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button({ vm.createMachine(newName); newName = "" }) { Text("Créer") }
        }
        vm.machines.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(vm.current == n, { vm.select(n) })
                Text(n, Modifier.weight(1f))
                TextButton({ vm.deleteMachine(n) }) { Text("Supprimer") }
            }
        }
        if (vm.current == null) { Text("Crée une machine pour commencer (étape 1 de la notice)."); return@Column }

        // Téléchargements en cours (catalogue, lien ou Hugging Face)
        vm.progress.forEach { (k, p) ->
            Text("Téléchargement : ${k.takeLast(40)}  $p %", color = grey)
            LinearProgressIndicator({ p / 100f }, Modifier.fillMaxWidth())
        }
        if (vm.status.isNotEmpty()) Text(vm.status)

        Text("IA du catalogue dans « ${vm.current} »", style = MaterialTheme.typography.titleMedium)
        vm.catalog.forEach { m ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(14.dp)) {
                    Text("${if (m.type == "chat") "💬" else "🖼"} ${m.name}")
                    if (m.minRam > vm.ramGb + 0.5) {
                        Text("⚠ ${m.minRam} Go de RAM conseillés (tu as ${"%.1f".format(vm.ramGb)} Go)", color = MaterialTheme.colorScheme.error)
                    }
                    when {
                        vm.progress[m.id] != null -> Text("Téléchargement en cours…", color = grey)
                        m.file in vm.installed -> TextButton({ vm.remove(m) }) { Text("Installé ✓  — désinstaller") }
                        else -> Button({ vm.install(m) }, Modifier.padding(top = 6.dp)) { Text("Installer") }
                    }
                }
            }
        }

        Text("Parcourir Hugging Face", style = MaterialTheme.typography.titleMedium)
        Text("Des milliers de modèles GGUF : cherche un nom (« qwen coder », « llama », « stable diffusion »).", color = grey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(vm.hfType == "chat", { vm.hfType = "chat" }, { Text("Chat / code") })
            FilterChip(vm.hfType == "image", { vm.hfType = "image" }, { Text("Images") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.hfQuery, { vm.hfQuery = it }, Modifier.weight(1f), label = { Text("Rechercher") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            Button({ vm.hfSearch() }) { Text("Chercher") }
        }
        if (vm.hfLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.hfResults.forEach { id -> TextButton({ vm.hfOpen(id) }) { Text(id) } }
        vm.hfModel?.let { id ->
            if (vm.hfFiles.isNotEmpty()) Text("Fichiers de $id (petit = rapide, gros = plus intelligent)", color = grey)
            vm.hfFiles.forEach { (f, size) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.substringAfterLast('/')}  ·  ${"%.1f".format(size / 1e9)} Go", Modifier.weight(1f))
                    Button({ vm.hfInstall(id, f) }) { Text("Installer") }
                }
            }
        }

        Text("Lien direct", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("https://…/modele.gguf") }, singleLine = true)
        Button({
            if (custom.startsWith("http")) {
                val name = custom.substringAfterLast('/').substringBefore('?')
                vm.install(ModelEntry("custom-$name", "chat", name, name, custom), custom)
            }
        }) { Text("Installer ce lien") }

        Text("Réglages de l'IA", style = MaterialTheme.typography.titleMedium)
        Text("Créativité (température) : ${"%.1f".format(vm.temperature)}", color = grey)
        Slider(vm.temperature, { vm.temperature = it }, valueRange = 0f..1.2f)
        Text("Mémoire de conversation (contexte) : ${vm.ctxSize} · plus grand = plus de RAM", color = grey)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(2048, 4096, 8192, 16384).forEach { c -> FilterChip(vm.ctxSize == c, { vm.ctxSize = c }, { Text("$c") }) }
        }
        Text("Threads CPU : ${vm.threads}", color = grey)
        Slider(vm.threads.toFloat(), { vm.threads = it.toInt() }, valueRange = 1f..8f, steps = 6)
        OutlinedTextField(
            vm.hfToken, { vm.saveToken(it) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Jeton Hugging Face (modèles réservés, facultatif)") },
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.height(16.dp))
    }
}
