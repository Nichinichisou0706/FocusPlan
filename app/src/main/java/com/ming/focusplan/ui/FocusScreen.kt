package com.ming.focusplan.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ming.focusplan.data.ScheduleBlockEntity
import com.ming.focusplan.data.TaskEntity
import com.ming.focusplan.planning.Planner
import com.ming.focusplan.focus.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private data class TimerUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    val secondsLeft: Int = 0,
    val segmentSeconds: Int = 0,
    val index: Int = 0,
    val total: Int = 0,
    val isBreak: Boolean = false,
    val taskTitle: String = ""
)

private data class LaunchableApp(val label: String, val packageName: String)

@Composable
fun FocusScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tasks by vm.tasks.collectAsState()
    val labels by vm.labels.collectAsState()
    val blocks by vm.blocks.collectAsState()
    val selectedTaskId by vm.selectedFocusTaskId.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var customMinutes by rememberSaveable { mutableIntStateOf(25) }
    var timerState by remember { mutableStateOf(readTimerState(context)) }
    var strictEnabled by remember { mutableStateOf(FocusPreferences.isStrictEnabled(context)) }
    var showWhitelist by remember { mutableStateOf(false) }
    var permissionRefresh by remember { mutableIntStateOf(0) }
    var pendingStart by remember { mutableStateOf<Intent?>(null) }
    var designMode by rememberSaveable { mutableIntStateOf(0) }
    var editingTask by remember { mutableStateOf<TaskEntity?>(null) }
    val customPlan = remember { mutableStateListOf<PomodoroSegment>() }

    val notificationGranted = permissionRefresh.let { hasNotificationPermission(context) }
    val accessibilityGranted = permissionRefresh.let { hasFocusAccessibility(context) }
    val usageGranted = permissionRefresh.let { hasUsageAccess(context) }
    val batteryGranted = permissionRefresh.let { isBatteryUnrestricted(context) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionRefresh++
        val start = pendingStart
        pendingStart = null
        if (granted && start != null) context.startForegroundService(start)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionRefresh++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            timerState = readTimerState(context)
            delay(500)
        }
    }

    val taskById = tasks.associateBy { it.id }
    val planningDate = planningDateAt(now)
    val dayStart = planningDate.atTime(6, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val dayEnd = planningDate.plusDays(1).atTime(2, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val todayBlocks = blocks.filter { block ->
        block.taskId != null && block.startAt < dayEnd && block.endAt > dayStart && taskById[block.taskId]?.completed == false
    }.sortedBy { it.startAt }
    val recommendedBlock = todayBlocks.firstOrNull { now in it.startAt until it.endAt }
    val selectedBlock = todayBlocks.firstOrNull { it.taskId == selectedTaskId }
    val selectedTask = selectedBlock?.taskId?.let(taskById::get)
    val taskMinutes = selectedBlock?.let { ((it.endAt - it.startAt) / 60_000L).toInt().coerceAtLeast(1) } ?: 0
    val presetPlan = if (taskMinutes > 0) PomodoroPlanner.plan(taskMinutes) else emptyList()
    val selectedPlan = if (designMode == 0) presetPlan else customPlan.toList()
    val customPlanValid = PomodoroPlanner.isValidCustomPlan(selectedPlan, taskMinutes)
    val knownLabels = remember(labels, tasks) {
        (labels.map { it.name } + tasks.map { it.subject }).filter { it.isNotBlank() && it != "未分类" }.distinct()
    }

    LaunchedEffect(todayBlocks.map { it.id }, recommendedBlock?.id, selectedTaskId) {
        if (selectedBlock == null) vm.selectFocusTask((recommendedBlock ?: todayBlocks.firstOrNull())?.taskId)
    }
    LaunchedEffect(selectedBlock?.id) {
        designMode = 0
        customPlan.clear()
        customPlan.addAll(presetPlan)
    }

    fun requestStart(intent: Intent) {
        if (hasNotificationPermission(context)) context.startForegroundService(intent)
        else if (Build.VERSION.SDK_INT >= 33) {
            pendingStart = intent
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else context.startForegroundService(intent)
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("专注", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        timerState.paused -> "计时暂停，回来后继续"
                        timerState.running && timerState.isBreak -> "休息一下，下一段再见"
                        timerState.running -> "保持节奏，只做眼前这一段"
                        else -> "选择一个任务，开始这段时间"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Mascot(
                mood = when {
                    timerState.paused -> MascotMood.BLOCKED
                    timerState.running && timerState.isBreak -> MascotMood.IDLE
                    timerState.running -> MascotMood.WORKING
                    else -> MascotMood.IDLE
                },
                contentDescription = null,
                size = 74.dp
            )
        }
        Spacer(Modifier.height(6.dp))

        if (timerState.running || timerState.paused) {
            ActiveTimerPanel(
                state = timerState,
                onResume = if (timerState.paused) {
                    { context.startService(Intent(context, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_RESUME)) }
                } else null,
                onPause = if (timerState.running && !timerState.paused) {
                    { context.startService(Intent(context, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_PAUSE)) }
                } else null,
                onStop = { context.startService(Intent(context, FocusTimerService::class.java).setAction(FocusTimerService.ACTION_STOP)) }
            )
        } else {
            QuickTimerPanel(customMinutes, onMinutesChange = { customMinutes = it.coerceIn(5, 180) }) {
                requestStart(
                    Intent(context, FocusTimerService::class.java)
                        .putExtra(FocusTimerService.EXTRA_DURATION_SECONDS, customMinutes * 60)
                        .putExtra(FocusTimerService.EXTRA_TASK_TITLE, "自由专注")
                        .putExtra(FocusTimerService.EXTRA_STRICT, strictEnabled)
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("今日任务番茄表", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${todayBlocks.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(10.dp))
        if (todayBlocks.isEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(6.dp)) {
                Text("今天的时间轴暂无已安排任务", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(todayBlocks, key = { "focus-block-${it.id}" }) { block ->
                    val task = block.taskId?.let(taskById::get) ?: return@items
                    FilterChip(
                        selected = selectedBlock?.id == block.id,
                        onClick = { vm.selectFocusTask(task.id) },
                        label = { Text("${Planner.label(block.startAt)}  ${task.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = if (recommendedBlock?.id == block.id) ({ Icon(Icons.Default.Star, null, Modifier.size(16.dp)) }) else null
                    )
                }
            }
        }

        if (selectedTask != null) {
            Spacer(Modifier.height(12.dp))
            TaskPomodoroPanel(
                task = selectedTask,
                block = selectedBlock,
                taskMinutes = taskMinutes,
                mode = designMode,
                onModeChange = { mode ->
                    designMode = mode
                    if (mode == 1 && customPlan.isEmpty()) customPlan.addAll(presetPlan)
                },
                plan = selectedPlan,
                customPlanValid = customPlanValid,
                onUpdateCustomPlan = { updated ->
                    customPlan.clear()
                    customPlan.addAll(PomodoroPlanner.renumber(updated))
                },
                onResetCustom = {
                    customPlan.clear()
                    customPlan.addAll(presetPlan)
                },
                canStart = !timerState.running && (designMode == 0 || customPlanValid),
                onOpenTask = { editingTask = selectedTask },
                onStart = {
                    requestStart(
                        Intent(context, FocusTimerService::class.java)
                            .putExtra(FocusTimerService.EXTRA_SEGMENT_DURATIONS, selectedPlan.map { it.durationMinutes * 60 }.toIntArray())
                            .putExtra(FocusTimerService.EXTRA_SEGMENT_BREAKS, selectedPlan.map { it.isBreak }.toBooleanArray())
                            .putExtra(FocusTimerService.EXTRA_TASK_TITLE, selectedTask.title)
                            .putExtra(FocusTimerService.EXTRA_STRICT, strictEnabled)
                    )
                }
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("严格模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        ListItem(
            headlineContent = { Text("专注时限制应用") },
            supportingContent = { Text(if (strictEnabled) "专注段拦截非白名单应用，休息段自动放开" else "当前关闭") },
            leadingContent = { Icon(Icons.Default.Lock, null) },
            trailingContent = {
                Switch(checked = strictEnabled, onCheckedChange = {
                    strictEnabled = it
                    FocusPreferences.setStrictEnabled(context, it)
                    if (timerState.running || timerState.paused) {
                        context.startService(
                            Intent(context, FocusTimerService::class.java)
                                .setAction(FocusTimerService.ACTION_SET_STRICT)
                                .putExtra(FocusTimerService.EXTRA_STRICT_ENABLED, it)
                        )
                    }
                })
            }
        )
        OutlinedButton(onClick = { showWhitelist = true }, Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text("管理应用白名单（${FocusPreferences.whitelist(context).size}）")
        }

        Spacer(Modifier.height(24.dp))
        Text("权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        PermissionRow("通知与音乐提醒", notificationGranted) {
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        PermissionRow("严格模式无障碍服务", accessibilityGranted) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        PermissionRow("使用情况访问", usageGranted) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        PermissionRow("后台运行不受限", batteryGranted) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }.onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showWhitelist) WhitelistDialog(context, onDismiss = { showWhitelist = false }) { permissionRefresh++ }
    editingTask?.let { task ->
        val taskBlock = todayBlocks.firstOrNull { it.taskId == task.id }
        TaskDetailsDialog(
            initial = task.toEditorValue(),
            knownLabels = knownLabels,
            dialogTitle = "编辑并细化任务",
            splitMessage = task.takeIf { it.parentTaskId != null }?.let {
                "这是同一任务的关联分块。退回待办会融合整组未完成分块。"
            },
            onDismiss = { editingTask = null },
            onDelete = { vm.deleteTask(task); editingTask = null },
            onReturnToPending = taskBlock?.let {
                {
                    vm.unschedule(it) { message -> android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show() }
                    editingTask = null
                }
            },
            onSave = { value ->
                vm.saveTask(task, value.title, value.detail, value.label, value.priority, value.minutes, value.plannedDayEpoch) { message ->
                    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                }
                editingTask = null
            }
        )
    }
}

@Composable
private fun ActiveTimerPanel(
    state: TimerUiState,
    onResume: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onStop: () -> Unit
) {
    val progress = if (state.segmentSeconds <= 0) 0f else 1f - state.secondsLeft.toFloat() / state.segmentSeconds
    Surface(color = if (state.isBreak) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state.paused) "计时已暂停" else if (state.isBreak) "休息" else state.taskTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text("%02d:%02d".format(state.secondsLeft / 60, state.secondsLeft % 60), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            LinearProgressIndicator({ progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("阶段 ${state.index + 1}/${state.total}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                onResume?.let {
                    Button(onClick = it) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("继续") }
                }
                onPause?.let {
                    Button(onClick = it) { Text("||"); Spacer(Modifier.width(6.dp)); Text("暂停") }
                }
                TextButton(onClick = onStop, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Close, null)
                    Spacer(Modifier.width(6.dp))
                    Text("结束计划")
                }
            }
        }
    }
}

@Composable
private fun QuickTimerPanel(minutes: Int, onMinutesChange: (Int) -> Unit, onStart: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("自由番茄钟", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                FilledTonalIconButton(onClick = { onMinutesChange(minutes - 5) }) { Text("−") }
                Text("$minutes 分钟", Modifier.width(112.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton(onClick = { onMinutesChange(minutes + 5) }) { Icon(Icons.Default.Add, "增加时长") }
            }
            LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(20, 25, 30, 45, 60)) { preset ->
                    FilterChip(selected = minutes == preset, onClick = { onMinutesChange(preset) }, label = { Text("$preset") })
                }
            }
            Button(onClick = onStart, Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("直接开始") }
        }
    }
}

@Composable
private fun TaskPomodoroPanel(
    task: TaskEntity,
    block: ScheduleBlockEntity,
    taskMinutes: Int,
    mode: Int,
    onModeChange: (Int) -> Unit,
    plan: List<PomodoroSegment>,
    customPlanValid: Boolean,
    onUpdateCustomPlan: (List<PomodoroSegment>) -> Unit,
    onResetCustom: () -> Unit,
    canStart: Boolean,
    onOpenTask: () -> Unit,
    onStart: () -> Unit
) {
    Surface(
        onClick = onOpenTask,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(5.dp)) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${Planner.label(block.startAt)} 至 ${Planner.label(block.endAt)} · $taskMinutes 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("智能预设", "自定义").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = mode == index,
                        onClick = { onModeChange(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (mode == 0) {
                PomodoroTable(plan)
            } else {
                CustomPlanEditor(plan, onUpdateCustomPlan, onResetCustom)
            }
            Spacer(Modifier.height(10.dp))
            val focusMinutes = plan.filterNot { it.isBreak }.sumOf { it.durationMinutes }
            val breakMinutes = plan.filter { it.isBreak }.sumOf { it.durationMinutes }
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(5.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("专注 $focusMinutes", style = MaterialTheme.typography.labelMedium)
                    Text("休息 $breakMinutes", style = MaterialTheme.typography.labelMedium)
                    Text("合计 ${focusMinutes + breakMinutes} · 目标 $taskMinutes±20", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (mode == 1 && !customPlanValid) {
                val difference = taskMinutes - PomodoroPlanner.totalMinutes(plan)
                val message = when {
                    plan.isEmpty() -> "至少保留一个专注块"
                    plan.first().isBreak || plan.last().isBreak || plan.zipWithNext().any { (left, right) -> left.isBreak == right.isBreak } -> "首尾须为专注，专注与休息需要交替"
                    difference > 20 -> "还需增加至少 ${difference - 20} 分钟"
                    difference < -20 -> "需减少至少 ${-difference - 20} 分钟"
                    else -> "分块设置无效"
                }
                Text(message, Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("开始这张番茄表")
            }
        }
    }
}

@Composable
private fun CustomPlanEditor(
    plan: List<PomodoroSegment>,
    onUpdate: (List<PomodoroSegment>) -> Unit,
    onReset: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        plan.forEachIndexed { index, segment ->
            Surface(color = if (segment.isBreak) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(5.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (segment.isBreak) Icons.Default.Refresh else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (segment.isBreak) "休息" else "专注", Modifier.width(40.dp), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    IconButton(onClick = {
                        onUpdate(plan.toMutableList().apply { this[index] = segment.copy(durationMinutes = (segment.durationMinutes - 5).coerceAtLeast(5)) })
                    }) { Text("−") }
                    Text("${segment.durationMinutes} 分", Modifier.width(48.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = {
                        onUpdate(plan.toMutableList().apply { this[index] = segment.copy(durationMinutes = (segment.durationMinutes + 5).coerceAtMost(180)) })
                    }) { Icon(Icons.Default.Add, "增加时长", Modifier.size(18.dp)) }
                    IconButton(onClick = {
                        onUpdate(plan.toMutableList().apply { this[index] = segment.copy(isBreak = !segment.isBreak) })
                    }) { Icon(Icons.Default.Edit, "切换专注或休息", Modifier.size(18.dp)) }
                    IconButton(onClick = { onUpdate(plan.toMutableList().apply { removeAt(index) }) }) {
                        Icon(Icons.Default.Delete, "删除分块", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = { onUpdate(plan + PomodoroSegment(20, isBreak = false)) },
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("专注") }
            OutlinedButton(
                onClick = { onUpdate(plan + PomodoroSegment(10, isBreak = true)) },
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("休息") }
            IconButton(onClick = onReset) { Icon(Icons.Default.Refresh, "恢复智能预设") }
        }
    }
}

@Composable
private fun PomodoroTable(plan: List<PomodoroSegment>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 4.dp)) {
        items(plan) { segment ->
            Surface(
                color = if (segment.isBreak) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(5.dp)
            ) {
                Column(Modifier.width(if (segment.isBreak) 72.dp else 94.dp).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (segment.isBreak) Icons.Default.Refresh else Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Text(if (segment.isBreak) "休息" else "番茄 ${segment.focusNumber}", style = MaterialTheme.typography.labelMedium)
                    Text("${segment.durationMinutes} 分钟", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(if (granted) "已就绪" else "需要配置") },
        leadingContent = { Icon(if (granted) Icons.Default.Check else Icons.Default.Info, null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) },
        trailingContent = { TextButton(onClick = onClick) { Text(if (granted) "查看" else "设置") } }
    )
}

@Composable
private fun WhitelistDialog(context: Context, onDismiss: () -> Unit, onChanged: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var whitelist by remember { mutableStateOf(FocusPreferences.whitelist(context)) }
    var apps by remember(context) { mutableStateOf(emptyList<LaunchableApp>()) }
    LaunchedEffect(context) { apps = loadLaunchableApps(context) }
    val visible = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用白名单") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索应用") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    if (apps.isEmpty()) item { Text("正在读取应用…", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(visible, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                val allowed = app.packageName !in whitelist
                                FocusPreferences.setWhitelisted(context, app.packageName, allowed)
                                whitelist = FocusPreferences.whitelist(context)
                                onChanged()
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = app.packageName in whitelist, onCheckedChange = { allowed ->
                                FocusPreferences.setWhitelisted(context, app.packageName, allowed)
                                whitelist = FocusPreferences.whitelist(context)
                                onChanged()
                            })
                            Column(Modifier.weight(1f)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

private fun readTimerState(context: Context): TimerUiState {
    val prefs = context.getSharedPreferences(FocusTimerService.PREFS, Context.MODE_PRIVATE)
    val running = prefs.getBoolean(FocusTimerService.KEY_RUNNING, false)
    val paused = prefs.getBoolean(FocusTimerService.KEY_PAUSED, false)
    val secondsLeft = when {
        !running -> 0
        paused -> (prefs.getLong(FocusTimerService.KEY_PAUSED_REMAINING_MILLIS, 0L) / 1_000L).toInt()
        else -> ((prefs.getLong(FocusTimerService.KEY_END_AT, 0L) - System.currentTimeMillis()).coerceAtLeast(0L) / 1_000L).toInt()
    }
    return TimerUiState(
        running = running,
        paused = paused,
        secondsLeft = secondsLeft,
        segmentSeconds = prefs.getInt(FocusTimerService.KEY_SEGMENT_DURATION_SECONDS, 0),
        index = prefs.getInt(FocusTimerService.KEY_INDEX, 0),
        total = prefs.getInt(FocusTimerService.KEY_TOTAL_SEGMENTS, 0),
        isBreak = prefs.getBoolean(FocusTimerService.KEY_IS_BREAK, false),
        taskTitle = prefs.getString(FocusTimerService.KEY_TASK_TITLE, "自由专注").orEmpty()
    )
}

private fun hasNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun hasFocusAccessibility(context: Context): Boolean {
    val target = ComponentName(context, FocusAccessibilityService::class.java)
    return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.split(':')?.mapNotNull(ComponentName::unflattenFromString)?.any { it == target } == true
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
}

private fun isBatteryUnrestricted(context: Context): Boolean = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun planningDateAt(nowMillis: Long): LocalDate {
    val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault())
    return if (now.toLocalTime().isBefore(LocalTime.of(2, 0))) now.toLocalDate().minusDays(1) else now.toLocalDate()
}

@Suppress("DEPRECATION")
private suspend fun loadLaunchableApps(context: Context): List<LaunchableApp> = withContext(Dispatchers.IO) {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    context.packageManager.queryIntentActivities(intent, 0)
        .map { LaunchableApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
