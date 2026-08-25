package com.ming.focusplan.ui

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ming.focusplan.data.*
import com.ming.focusplan.focus.FocusTimerService
import com.ming.focusplan.planning.Planner
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun FocusPlanApp(container: AppContainer) {
    val vm: MainViewModel = viewModel(factory = MainViewModelFactory(container))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tasks by vm.tasks.collectAsState()
    val blocks by vm.blocks.collectAsState()
    val assistantState by vm.assistantUiState.collectAsState()
    val destinations = listOf(
        AppDestination("任务", Icons.Default.List),
        AppDestination("时间轴", Icons.Default.DateRange),
        AppDestination("专注", Icons.Default.PlayArrow),
        AppDestination("助手", null, branded = true),
        AppDestination("设置", Icons.Default.Settings)
    )
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activeTaskIds = tasks.filterNot { it.completed }.mapTo(mutableSetOf()) { it.id }
    val hasActiveTask = blocks.any { block ->
        block.taskId in activeTaskIds && System.currentTimeMillis() in block.startAt until block.endAt
    }
    val mood = when {
        tab == 3 && assistantState.error != null -> MascotMood.BLOCKED
        tab == 3 && assistantState.loading -> MascotMood.WORKING
        tab == 3 -> MascotMood.WELCOME
        tab == 2 || hasActiveTask -> MascotMood.WORKING
        tasks.isNotEmpty() && tasks.all { it.completed } -> MascotMood.REVIEW
        tasks.isEmpty() -> MascotMood.WELCOME
        else -> MascotMood.IDLE
    }

    FocusPlanBackdrop(mood) {
        if (isLandscape) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)) {
                    Spacer(Modifier.height(8.dp))
                    BrandMark(Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    destinations.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { DestinationIcon(item, selected = tab == index) },
                            label = { Text(item.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = if (mood == MascotMood.BLOCKED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
                FocusPlanContent(tab, vm, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        NavigationBar(
                            modifier = Modifier.height(78.dp),
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                            tonalElevation = 0.dp
                        ) {
                            destinations.forEachIndexed { index, item ->
                                NavigationBarItem(
                                    selected = tab == index,
                                    onClick = { tab = index },
                                    icon = { DestinationIcon(item, selected = tab == index) },
                                    label = { Text(item.label, maxLines = 1) },
                                    alwaysShowLabel = true,
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = if (mood == MascotMood.BLOCKED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                FocusPlanContent(tab, vm, Modifier.padding(padding))
            }
        }
    }
}

private data class AppDestination(val label: String, val icon: ImageVector?, val branded: Boolean = false)

@Composable
private fun DestinationIcon(destination: AppDestination, selected: Boolean) {
    if (destination.branded) {
        BrandMark(Modifier.size(if (selected) 30.dp else 26.dp))
    } else {
        Icon(destination.icon ?: Icons.Default.Star, contentDescription = null)
    }
}

@Composable
private fun FocusPlanContent(tab: Int, vm: MainViewModel, modifier: Modifier) {
    when (tab) {
        0 -> TasksScreen(vm, modifier)
        1 -> TimelineScreen(vm, modifier)
        2 -> FocusScreen(vm, modifier)
        3 -> AssistantScreen(vm, modifier)
        else -> SettingsScreen(vm, modifier)
    }
}

@Composable
private fun TodayScreen(vm: MainViewModel, modifier: Modifier) {
    val tasks by vm.tasks.collectAsState()
    val blocks by vm.blocks.collectAsState()
    var input by remember { mutableStateOf("") }
    var assistantInput by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.MEDIUM) }
    var assistantReply by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("${LocalDate.now().monthValue}月${LocalDate.now().dayOfMonth}日 · 今日", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("把重要的事，放进今天", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("添加一个任务…") }, singleLine = true)
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { vm.addTask(input, priority); input = "" }) { Icon(Icons.Default.Add, "添加") }
        }
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { p -> FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) }) }
            TextButton(onClick = { vm.scheduleDay(LocalDate.now()) }) { Text("自动排程") }
        }
        OutlinedTextField(
            value = assistantInput,
            onValueChange = { assistantInput = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("告诉助手：我今天不知道先做什么…") },
            trailingIcon = {
                IconButton(onClick = { vm.askAssistant(assistantInput) { assistantReply = it }; assistantInput = "" }, enabled = assistantInput.isNotBlank()) {
                    Icon(Icons.Default.Check, "询问助手")
                }
            },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        assistantReply?.let { Text(it, Modifier.fillMaxWidth().padding(bottom = 8.dp), color = MaterialTheme.colorScheme.primary) }
        Text("时间轴", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            if (blocks.isEmpty()) item { EmptyTimeline() }
            items(blocks, key = { "schedule-${it.id}" }) { block -> TimelineCard(block) }
            item {
                Text("任务池", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            }
            items(tasks, key = { "task-${it.id}" }) { task -> TaskRow(task, vm::toggle) }
        }
    }
}

@Composable private fun EmptyTimeline() = Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text("还没有时间块，添加任务后点“自动排程”", Modifier.padding(16.dp)) }
@Composable private fun TimelineCard(block: ScheduleBlockEntity) = Card(shape = RoundedCornerShape(8.dp)) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Text(Planner.label(block.startAt), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(58.dp)); Column { Text(block.title, fontWeight = FontWeight.SemiBold); Text("${Planner.label(block.startAt)} 至 ${Planner.label(block.endAt)} · ${Priority.fromRank(block.priority).label}优先", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun TaskRow(task: TaskEntity, toggle: (TaskEntity) -> Unit) = ListItem(headlineContent = { Text(task.title, fontWeight = FontWeight.Medium) }, supportingContent = { Text("${task.subject} · ${Priority.fromRank(task.priority).label}优先 · ${task.estimatedMinutes}分钟") }, leadingContent = { Checkbox(task.completed, { toggle(task) }) })

@Composable
private fun SettingsScreen(vm: MainViewModel, modifier: Modifier) {
    val models by vm.models.collectAsState()
    var showModelDialog by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<ModelProfileEntity?>(null) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledIconButton(onClick = { editingModel = null; showModelDialog = true }) { Icon(Icons.Default.Add, "添加模型") }
        }
        Spacer(Modifier.height(16.dp))
        Text("智能助手", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("支持 OpenAI-compatible 接口。开启的模型按列表顺序尝试，异常时自动切换。", color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Text("选择模型", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        OutlinedCard(modifier = Modifier.fillMaxWidth().height(232.dp), shape = RoundedCornerShape(6.dp)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (models.isEmpty()) {
                    item { Text("尚未配置模型，点右上角添加。", Modifier.padding(16.dp), color = Color.Gray) }
                } else {
                    items(models, key = { "model-${it.id}" }) { profile ->
                        ListItem(
                            modifier = Modifier.clickable { editingModel = profile; showModelDialog = true },
                            headlineContent = { Text(profile.name, fontWeight = FontWeight.Medium) },
                            supportingContent = { Text(profile.modelId, maxLines = 1) },
                            leadingContent = { Icon(Icons.Default.Info, null) },
                            trailingContent = { Switch(checked = profile.enabled, onCheckedChange = { vm.setModelEnabled(profile, it) }) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if (showModelDialog) ModelDetailsDialog(
        profile = editingModel,
        vm = vm,
        onDismiss = { showModelDialog = false }
    ) { name, url, model, key ->
        vm.saveModel(editingModel, name, url, model, key)
        showModelDialog = false
    }
}

@Composable
private fun ModelDetailsDialog(
    profile: ModelProfileEntity?,
    vm: MainViewModel,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name ?: "DeepSeek") }
    var url by remember(profile?.id) { mutableStateOf(profile?.baseUrl ?: "https://api.deepseek.com") }
    var model by remember(profile?.id) { mutableStateOf(profile?.modelId ?: "deepseek-chat") }
    var key by remember { mutableStateOf("") }
    var availableModels by remember(profile?.id) { mutableStateOf(emptyList<String>()) }
    var catalogStatus by remember(profile?.id) { mutableStateOf<String?>(null) }
    val canUseStoredKey = profile != null && url.trim().trimEnd('/') == profile.baseUrl.trim().trimEnd('/')

    LaunchedEffect(url, key, canUseStoredKey) {
        if (url.startsWith("http") && (key.isNotBlank() || canUseStoredKey)) {
            delay(700)
            catalogStatus = "正在获取模型…"
            vm.loadAvailableModels(url, key, profile?.apiKeyAlias?.takeIf { canUseStoredKey }) { result ->
                result.onSuccess {
                    availableModels = it
                    catalogStatus = "已获取 ${it.size} 个模型"
                }.onFailure {
                    availableModels = emptyList()
                    catalogStatus = "无法获取模型，可手动填写 Model ID（${it.message}）"
                }
            }
        } else {
            availableModels = emptyList()
            catalogStatus = null
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (profile == null) "添加模型" else "模型设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true)
            OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true)
            OutlinedTextField(model, { model = it }, label = { Text("Model ID") }, singleLine = true)
            OutlinedTextField(
                key, { key = it },
                label = { Text(if (profile == null) "API Key" else "新 API Key（留空则不更改）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            catalogStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (availableModels.isNotEmpty()) {
                OutlinedCard(Modifier.fillMaxWidth().height(144.dp), shape = RoundedCornerShape(6.dp)) {
                    LazyColumn {
                        items(availableModels, key = { "catalog-$it" }) { modelId ->
                            Row(
                                Modifier.fillMaxWidth().clickable { model = modelId }.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = model == modelId, onClick = { model = modelId })
                                Text(modelId, Modifier.padding(start = 6.dp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onSave(name, url, model, key) }, enabled = name.isNotBlank() && url.isNotBlank() && model.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

class MainViewModelFactory(private val container: AppContainer) : androidx.lifecycle.ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T }
