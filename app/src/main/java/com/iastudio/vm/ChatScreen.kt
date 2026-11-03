package com.iastudio.vm

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val chats = vm.allModels.filter { it.type == "chat" && it.file in vm.installed }
    var sel by remember { mutableStateOf<ModelEntry?>(null) }
    val model = sel?.takeIf { it in chats } ?: chats.firstOrNull()
    var text by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val attach = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val t = try {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.take(30000) ?: ""
            } catch (e: Exception) { "" }
            if (t.isNotBlank()) text += "\n```\n$t\n```\n"
        }
    }
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }
    if (model == null) {
        Text("Installe d'abord une IA de chat dans l'onglet Machines.\nPour le code, choisis « Qwen2.5-Coder ».")
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatMode.values().forEach { m -> FilterChip(vm.mode == m, { vm.changeMode(m) }, { Text(m.label) }) }
        }
        ModelPicker(chats, model) { sel = it }
        if (vm.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("✻  ${vm.mode.hello}", style = MaterialTheme.typography.headlineMedium)
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(vertical = 8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(vm.messages.toList()) { (r, c) ->
                    if (r == "user") {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                                Text(c.take(1500), Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                            }
                        }
                    } else {
                        Row {
                            Text("✻ ", color = MaterialTheme.colorScheme.primary)
                            RichText(c)
                        }
                    }
                }
            }
        }
        if (vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodyMedium)
        if (vm.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
            TextButton({ vm.stopWork() }) { Text("Arrêter") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f), label = { Text("Écris ton message") },
                shape = RoundedCornerShape(20.dp), maxLines = 5,
            )
            Spacer(Modifier.width(8.dp))
            Button({ vm.send(text, model); text = "" }, enabled = !vm.busy) { Text("Envoyer") }
        }
        Row {
            TextButton({ attach.launch("*/*") }) { Text("Joindre un fichier") }
            if (vm.messages.isNotEmpty()) TextButton({ vm.clearChat() }) { Text("Nouvelle conversation") }
        }
    }
}
