package com.ming.focusplan.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ming.focusplan.data.Priority
import com.ming.focusplan.data.ScheduleBlockEntity
import com.ming.focusplan.data.TaskEntity
import com.ming.focusplan.assistant.ExcludedTime
import com.ming.focusplan.assistant.PLANNING_DAY_END_MINUTE
import com.ming.focusplan.assistant.PLANNING_DAY_START_MINUTE
import com.ming.focusplan.planning.Planner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private data class TypeStyle(val accent: Color, val container: Color, val glyph: String)
private data class TimelineExclusionBlock(val id: String, val title: String, val startAt: Long, val endAt: Long)

private val typePalette = listOf(
    Color(0xFF315E9B) to Color(0xFFE8F0FC),
    Color(0xFF8C4F7D) to Color(0xFFF7EAF3),
    Color(0xFF2F725B) to Color(0xFFE5F3ED),
    Color(0xFFA05A32) to Color(0xFFFAEDE5),
    Color(0xFF76631C) to Color(0xFFF7F1D8),
    Color(0xFF5F5A82) to Color(0xFFEDEBF6)
)

private fun typeStyle(type: String): TypeStyle {
    val normalized = type.trim().ifBlank { "其他" }
    val colors = typePalette[(normalized.hashCode() and Int.MAX_VALUE) % typePalette.size]
    val glyph = when {
        normalized.contains("数学") -> "∑"
        normalized.contains("英语") || normalized.contains("英文") -> "A"
        normalized.contains("政治") -> "政"
        normalized.contains("专业") -> "专"
        normalized.contains("复盘") -> "✓"
        else -> normalized.take(1)
    }
    return TypeStyle(colors.first, colors.second, glyph)
}

@Composable
fun TasksScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsState()
    val labels by vm.labels.collectAsState()
    val screenState by vm.taskScreenState.collectAsState()
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var labelToDelete by remember { mutableStateOf<String?>(null) }
    val knownLabels = remember(labels, tasks) {
        (labels.map { it.name } + tasks.map { it.subject }).filter { it.isNotBlank() && it != "未分类" }.distinct()
    }
    val visibleTasks = if (screenState.page == 0) tasks.filterNot { it.completed } else tasks.filter { it.completed }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("任务", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${tasks.count { !it.completed }} 项待完成", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(onClick = { editing = null; showEditor = true }) { Icon(Icons.Default.Add, "新建任务") }
        }
        Spacer(Modifier.height(12.dp))
        TabRow(selectedTabIndex = screenState.page) {
            Tab(selected = screenState.page == 0, onClick = { vm.setTaskPage(0) }, text = { Text("未完成 ${tasks.count { !it.completed }}") })
            Tab(selected = screenState.page == 1, onClick = { vm.setTaskPage(1) }, text = { Text("已完成 ${tasks.count { it.completed }}") })
        }
        if (screenState.page == 0) {
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("按优先级", "按标签").forEachIndexed { index, title ->
                    SegmentedButton(
                        selected = screenState.grouping == index,
                        onClick = { vm.setTaskGrouping(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.weight(1f)
                    ) { Text(title) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            if (visibleTasks.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Mascot(
                            mood = if (screenState.page == 0) MascotMood.REVIEW else MascotMood.IDLE,
                            contentDescription = null,
                            size = 118.dp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (screenState.page == 0) "今天清空了，做得不错" else "完成的任务会收在这里",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (screenState.page == 0) "新建任务，或去时间轴安排下一步" else "勾选任务后即可在此复盘",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else if (screenState.page == 1) {
                items(visibleTasks, key = { "completed-task-${it.id}" }) { task ->
                    TaskCard(task = task, onClick = { editing = task; showEditor = true }, onToggle = { vm.toggle(task) })
                }
            } else {
                val groups = if (screenState.grouping == 0) {
                    Priority.entries.map { priority ->
                        TaskGroup("priority-${priority.rank}", "${priority.label}优先级", visibleTasks.filter { it.priority == priority.rank }, null)
                    }
                } else {
                    (knownLabels + "未分类").map { label ->
                        TaskGroup("label-$label", label, visibleTasks.filter { it.subject.ifBlank { "未分类" } == label }, label.takeUnless { it == "未分类" })
                    }
                }
                groups.forEach { group ->
                    val isExpanded = group.key !in screenState.collapsedGroups
                    item(key = "header-${group.key}") {
                        TaskGroupHeader(
                            title = group.title,
                            count = group.tasks.size,
                            expanded = isExpanded,
                            onToggle = { vm.toggleTaskGroup(group.key) },
                            onDelete = group.deletableLabel?.let { { labelToDelete = it } }
                        )
                    }
                    if (isExpanded) {
                        if (group.tasks.isEmpty()) {
                            item(key = "empty-${group.key}") {
                                Text("此栏暂无任务", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        } else {
                            items(group.tasks, key = { "${group.key}-task-${it.id}" }) { task ->
                                TaskCard(task = task, onClick = { editing = task; showEditor = true }, onToggle = { vm.toggle(task) })
                            }
                        }
                    }
                    item(key = "space-${group.key}") { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }

    if (showEditor) TaskEditorDialog(
        task = editing,
        knownLabels = knownLabels,
        onDismiss = { showEditor = false },
        onDelete = editing?.let { task -> { vm.deleteTask(task); showEditor = false } },
        onSave = { title, type, priority, minutes ->
            vm.saveTask(editing, title, type, priority, minutes)
            showEditor = false
        }
    )

    labelToDelete?.let { label ->
        AlertDialog(
            onDismissRequest = { labelToDelete = null },
            icon = { Icon(Icons.Default.Delete, null) },
            title = { Text("删除标签“$label”？") },
            text = { Text("标签会从列表移除，其中的任务将保留并归入“未分类”。") },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteLabel(label); labelToDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除标签") }
            },
            dismissButton = { TextButton(onClick = { labelToDelete = null }) { Text("取消") } }
        )
    }
}

private data class TaskGroup(val key: String, val title: String, val tasks: List<TaskEntity>, val deletableLabel: String?)

@Composable
private fun TaskGroupHeader(title: String, count: Int, expanded: Boolean, onToggle: () -> Unit, onDelete: (() -> Unit)?) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "收起" else "展开")
            Spacer(Modifier.width(8.dp))
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除标签", tint = MaterialTheme.colorScheme.error) }
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskEntity, onClick: () -> Unit, onToggle: () -> Unit) {
    val style = typeStyle(task.subject)
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 98.dp),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = style.container)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(style.accent),
                contentAlignment = Alignment.Center
            ) { Text(style.glyph, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${task.subject}  ·  ${task.estimatedMinutes} 分钟  ·  ${Priority.fromRank(task.priority).label}优先", style = MaterialTheme.typography.bodySmall, color = style.accent)
            }
            Checkbox(checked = task.completed, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun TaskEditorDialog(
    task: TaskEntity?,
    knownLabels: List<String>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (String, String, Priority, Int) -> Unit
) {
    var title by remember(task?.id) { mutableStateOf(task?.title.orEmpty()) }
    var type by remember(task?.id) { mutableStateOf(task?.subject?.takeUnless { it == "未分类" }.orEmpty()) }
    var priority by remember(task?.id) { mutableStateOf(Priority.fromRank(task?.priority ?: Priority.MEDIUM.rank)) }
    var minutesText by remember(task?.id) { mutableStateOf((task?.estimatedMinutes ?: 50).toString()) }
    val minutes = minutesText.toIntOrNull()?.coerceIn(10, 480) ?: 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "新建任务" else "编辑任务") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("任务名称") }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
                OutlinedTextField(type, { type = it }, label = { Text("合集标签（可选）") }, placeholder = { Text("留空即未分类，也可输入新标签") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = type.isBlank(), onClick = { type = "" }, label = { Text("未分类") }) }
                    items(knownLabels, key = { "type-$it" }) { item ->
                        FilterChip(selected = type == item, onClick = { type = item }, label = { Text(item) })
                    }
                }
                Text("优先级", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { item -> FilterChip(selected = priority == item, onClick = { priority = item }, label = { Text(item.label) }) }
                }
                Text("预计时长", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { minutesText = (minutes.coerceAtLeast(20) - 10).toString() }) { Text("−") }
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { value -> if (value.all(Char::isDigit)) minutesText = value },
                        modifier = Modifier.width(116.dp).padding(horizontal = 8.dp),
                        suffix = { Text("分钟") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    FilledTonalIconButton(onClick = { minutesText = (minutes.coerceAtLeast(0) + 10).coerceAtMost(480).toString() }) { Icon(Icons.Default.Add, "增加时长") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, type, priority, minutes) }, enabled = title.isNotBlank() && minutes in 10..480) { Text("保存") } },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") } }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

private enum class TimelineMode(val label: String) { DAY("日"), THREE_DAY("三日"), WEEK("周"), MONTH("月") }

@Composable
fun TimelineScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val tasks by vm.tasks.collectAsState()
    val blocks by vm.blocks.collectAsState()
    val scheduledIds by vm.scheduledTaskIds.collectAsState()
    val assistantPreset by vm.assistantPreset.collectAsState()
    var mode by rememberSaveable { mutableStateOf(TimelineMode.DAY) }
    var selectedDateEpochDay by rememberSaveable { mutableLongStateOf(currentPlanningDate().toEpochDay()) }
    var selectedTaskId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedBlock by remember { mutableStateOf<ScheduleBlockEntity?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val selectedDate = LocalDate.ofEpochDay(selectedDateEpochDay)
    val today = currentPlanningDate()
    val canPlaceTasks = !selectedDate.isBefore(today)
    val pending = tasks.filter { !it.completed && it.id !in scheduledIds }
    val taskById = tasks.associateBy { it.id }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(pending.map { it.id }) {
        if (selectedTaskId != null && pending.none { it.id == selectedTaskId }) selectedTaskId = null
    }

    Box(modifier.fillMaxSize()) {
        val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
            TimelineControls(
                modifier = controlsModifier,
                mode = mode,
                selectedDate = selectedDate,
                isToday = mode == TimelineMode.DAY && selectedDate == today,
                pending = pending,
                selectedTaskId = selectedTaskId,
                allowTaskSelection = canPlaceTasks,
                onModeChange = { mode = it },
                onPrevious = { selectedDateEpochDay = shiftDate(mode, selectedDate, -1).toEpochDay() },
                onNext = { selectedDateEpochDay = shiftDate(mode, selectedDate, 1).toEpochDay() },
                onResetDay = {
                    vm.resetDay(selectedDate) { message -> scope.launch { snackbar.showSnackbar(message) } }
                },
                onTaskSelect = { selectedTaskId = if (selectedTaskId == it.id) null else it.id }
            )
        }
        val timeline: @Composable (Modifier) -> Unit = { timelineModifier ->
            TimelineContent(
                modifier = timelineModifier,
                mode = mode,
                selectedDate = selectedDate,
                blocks = blocks,
                exclusions = assistantPreset.excludedTimes.filter { it.enabled },
                taskById = taskById,
                selectedTask = pending.firstOrNull { it.id == selectedTaskId }.takeIf { canPlaceTasks },
                allowBlockChanges = canPlaceTasks,
                onPlace = { task, start ->
                    vm.placeTask(task, start, atMillis(selectedDate, PLANNING_DAY_END_MINUTE)) { message ->
                        scope.launch {
                            snackbar.showSnackbar(message ?: "已安排到 ${Planner.label(start)}")
                        }
                    }
                },
                onBlockClick = { selectedBlock = it },
                onDayClick = {
                    selectedDateEpochDay = it.toEpochDay()
                    mode = TimelineMode.DAY
                }
            )
        }

        if (isLandscape) {
            Row(Modifier.fillMaxSize()) {
                controls(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()))
                VerticalDivider()
                timeline(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                controls(Modifier.fillMaxWidth())
                timeline(Modifier.weight(1f).fillMaxWidth())
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }

    selectedBlock?.let { block ->
        val task = block.taskId?.let(taskById::get)
        AlertDialog(
            onDismissRequest = { selectedBlock = null },
            title = { Text(block.title) },
            text = { Text("${task?.subject ?: "计划"}  ·  ${Planner.label(block.startAt)} 至 ${Planner.label(block.endAt)}  ·  ${Priority.fromRank(block.priority).label}优先") },
            confirmButton = { TextButton(onClick = { vm.unschedule(block); selectedBlock = null }) { Text("退回待办") } },
            dismissButton = { TextButton(onClick = { selectedBlock = null }) { Text("关闭") } }
        )
    }
}

@Composable
private fun TimelineControls(
    modifier: Modifier,
    mode: TimelineMode,
    selectedDate: LocalDate,
    isToday: Boolean,
    pending: List<TaskEntity>,
    selectedTaskId: Long?,
    allowTaskSelection: Boolean,
    onModeChange: (TimelineMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onResetDay: () -> Unit,
    onTaskSelect: (TaskEntity) -> Unit
) {
    Column(modifier) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("时间轴", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = onResetDay) { Icon(Icons.Default.Refresh, "将本天任务全部退回待安排") }
        }
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            TimelineMode.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = mode == item,
                    onClick = { onModeChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(index, TimelineMode.entries.size),
                    modifier = Modifier.weight(1f)
                ) { Text(item.label) }
            }
        }
        DateNavigator(title = timelineTitle(mode, selectedDate), isToday = isToday, onPrevious = onPrevious, onNext = onNext)
        PendingTaskTray(pending, selectedTaskId.takeIf { allowTaskSelection }, allowTaskSelection, onTaskSelect)
    }
}

@Composable
private fun TimelineContent(
    modifier: Modifier,
    mode: TimelineMode,
    selectedDate: LocalDate,
    blocks: List<ScheduleBlockEntity>,
    exclusions: List<ExcludedTime>,
    taskById: Map<Long, TaskEntity>,
    selectedTask: TaskEntity?,
    allowBlockChanges: Boolean,
    onPlace: (TaskEntity, Long) -> Unit,
    onBlockClick: (ScheduleBlockEntity) -> Unit,
    onDayClick: (LocalDate) -> Unit
) {
    Box(modifier) {
        when (mode) {
            TimelineMode.DAY -> DayTimeline(
                date = selectedDate,
                blocks = blocksForPlanningDay(blocks, selectedDate),
                exclusions = if (selectedDate == currentPlanningDate()) {
                    exclusionsForPlanningDay(exclusions, selectedDate)
                } else {
                    emptyList()
                },
                taskById = taskById,
                selectedTask = selectedTask,
                onPlace = onPlace,
                onBlockClick = onBlockClick.takeIf { allowBlockChanges }
            )
            TimelineMode.THREE_DAY -> MultiDayTimeline(selectedDate, 3, blocks, taskById, onDayClick)
            TimelineMode.WEEK -> {
                val monday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                MultiDayTimeline(monday, 7, blocks, taskById, onDayClick)
            }
            TimelineMode.MONTH -> MonthTimeline(selectedDate, blocks, taskById, onDayClick)
        }
    }
}

@Composable
private fun DateNavigator(title: String, isToday: Boolean, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.Default.KeyboardArrowLeft, "上一时段") }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (isToday) {
                Spacer(Modifier.width(8.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                    Text("今日", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
        }
        IconButton(onClick = onNext) { Icon(Icons.Default.KeyboardArrowRight, "下一时段") }
    }
}

@Composable
private fun PendingTaskTray(tasks: List<TaskEntity>, selectedId: Long?, enabled: Boolean, onSelect: (TaskEntity) -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)).padding(vertical = 8.dp)) {
        Text("待安排  ${tasks.size}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelLarge)
        if (!enabled) {
            Text("历史规划日只读，右上按钮可回收未完成任务", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(6.dp))
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(82.dp), contentAlignment = Alignment.Center) { Text("待办区为空", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tasks, key = { "pending-${it.id}" }) { task -> PendingBrick(task, selectedId == task.id, enabled) { onSelect(task) } }
            }
        }
    }
}

@Composable
private fun PendingBrick(task: TaskEntity, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val style = typeStyle(task.subject)
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.width(196.dp).height(92.dp).then(if (selected) Modifier.border(2.dp, style.accent, RoundedCornerShape(6.dp)) else Modifier),
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = style.container)
    ) {
        Row(Modifier.fillMaxSize().padding(10.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(4.dp)).background(style.accent), contentAlignment = Alignment.Center) { Text(style.glyph, color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(task.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text("${task.subject} · ${task.estimatedMinutes}分钟 · ${Priority.fromRank(task.priority).label}", style = MaterialTheme.typography.labelSmall, color = style.accent, maxLines = 1)
            }
        }
    }
}

@Composable
private fun DayTimeline(
    date: LocalDate,
    blocks: List<ScheduleBlockEntity>,
    exclusions: List<TimelineExclusionBlock>,
    taskById: Map<Long, TaskEntity>,
    selectedTask: TaskEntity?,
    onPlace: (TaskEntity, Long) -> Unit,
    onBlockClick: ((ScheduleBlockEntity) -> Unit)?
) {
    val startMinute = PLANNING_DAY_START_MINUTE
    val endMinute = PLANNING_DAY_END_MINUTE
    val cellMinutes = 10
    val cellHeight = 24.dp
    val slotCount = (endMinute - startMinute) / cellMinutes
    val totalHeight = cellHeight * slotCount
    val timelineStart = atMillis(date, startMinute)
    val timelineEnd = atMillis(date, endMinute)
    val scrollState = rememberScrollState()
    val cellHeightPx = with(LocalDensity.current) { cellHeight.toPx() }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(date) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(30_000)
        }
    }
    val currentSlotIndex = if (nowMillis in timelineStart until timelineEnd) {
        ((nowMillis - timelineStart) / (cellMinutes * 60_000L)).toInt().coerceIn(0, slotCount - 1)
    } else null

    Row(
        Modifier.fillMaxSize().verticalScroll(scrollState).padding(start = 12.dp, end = 12.dp, bottom = 20.dp)
    ) {
        Box(Modifier.width(76.dp).height(totalHeight)) {
            currentSlotIndex?.takeIf { it > 0 }?.let { index ->
                Box(
                    Modifier.fillMaxWidth().height(cellHeight * index).zIndex(-1f)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                )
            }
            for (minuteOfDay in startMinute..endMinute step 30) {
                val top = if (minuteOfDay == endMinute) totalHeight - 18.dp else cellHeight * ((minuteOfDay - startMinute) / cellMinutes)
                val clockMinute = Math.floorMod(minuteOfDay, 24 * 60)
                Text(
                    if (minuteOfDay == 24 * 60) "次日 00:00" else "%02d:%02d".format(clockMinute / 60, clockMinute % 60),
                    modifier = Modifier.offset(y = top + 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            currentSlotIndex?.let { index ->
                val top = cellHeight * index
                Box(
                    Modifier.align(Alignment.TopEnd).width(28.dp).height(2.dp).offset(y = top - 1.dp)
                        .zIndex(2f).background(MaterialTheme.colorScheme.error)
                )
            }
        }
        Box(Modifier.weight(1f).height(totalHeight)) {
            Box(
                Modifier.fillMaxSize().pointerInput(date, selectedTask?.id) {
                    if (selectedTask != null) {
                        detectTapGestures { offset ->
                            val index = (offset.y / cellHeightPx).toInt().coerceIn(0, slotCount - 1)
                            onPlace(selectedTask, atMillis(date, startMinute + index * cellMinutes))
                        }
                    }
                }
            )
            repeat(slotCount) { index ->
                val majorLine = index % 3 == 0
                Box(
                    Modifier.fillMaxWidth().height(cellHeight).offset(y = cellHeight * index)
                ) {
                    HorizontalDivider(
                        modifier = Modifier.align(Alignment.TopCenter),
                        thickness = if (majorLine) 1.dp else 0.5.dp,
                        color = if (majorLine) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
            HorizontalDivider(Modifier.align(Alignment.BottomCenter), thickness = 1.dp, color = MaterialTheme.colorScheme.outline)

            currentSlotIndex?.takeIf { it > 0 }?.let { index ->
                Box(
                    Modifier.fillMaxWidth().height(cellHeight * index).zIndex(0.5f)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                )
            }

            exclusions.filter { it.startAt < timelineEnd && it.endAt > timelineStart }.forEach { exclusion ->
                val visibleStart = exclusion.startAt.coerceAtLeast(timelineStart)
                val visibleEnd = exclusion.endAt.coerceAtMost(timelineEnd)
                val top = ((visibleStart - timelineStart).toFloat() / 600_000f * cellHeight.value).dp
                val height = ((visibleEnd - visibleStart).toFloat() / 600_000f * cellHeight.value).dp
                Box(Modifier.fillMaxWidth().offset(y = top).padding(horizontal = 22.dp).zIndex(0.8f)) {
                    ExclusionBrick(exclusion, height)
                }
            }

            blocks.filter { it.startAt < timelineEnd && it.endAt > timelineStart }.forEach { block ->
                val visibleStart = block.startAt.coerceAtLeast(timelineStart)
                val visibleEnd = block.endAt.coerceAtMost(timelineEnd)
                val top = ((visibleStart - timelineStart).toFloat() / 600_000f * cellHeight.value).dp
                val height = ((visibleEnd - visibleStart).toFloat() / 600_000f * cellHeight.value).dp
                Box(Modifier.fillMaxWidth().offset(y = top).padding(horizontal = 14.dp).zIndex(1f)) {
                    ScheduleBrick(block, block.taskId?.let(taskById::get), height, onBlockClick)
                }
            }
            currentSlotIndex?.let { index ->
                val top = cellHeight * index
                Box(
                    Modifier.fillMaxWidth().height(2.dp).offset(y = top - 1.dp).zIndex(2f)
                        .background(MaterialTheme.colorScheme.error)
                )
                Box(
                    Modifier.size(8.dp).offset(x = (-4).dp, y = top - 4.dp).zIndex(2f)
                        .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.error)
                )
            }
        }
    }
}

@Composable
private fun ScheduleBrick(block: ScheduleBlockEntity, task: TaskEntity?, height: Dp, onClick: ((ScheduleBlockEntity) -> Unit)?) {
    val style = typeStyle(task?.subject ?: "计划")
    val completed = task?.completed == true
    val accent = if (completed) MaterialTheme.colorScheme.outline else style.accent
    val container = if (completed) MaterialTheme.colorScheme.surfaceVariant else style.container
    val interaction = if (onClick == null) Modifier else Modifier.clickable { onClick(block) }
    val tiny = height < 56.dp
    val compact = height < 76.dp
    Surface(
        modifier = Modifier.fillMaxWidth().height(height).then(interaction),
        color = container,
        shape = RoundedCornerShape(5.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (tiny) 5.dp else 10.dp, vertical = if (tiny) 2.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(if (tiny) 3.dp else 5.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(accent))
            Spacer(Modifier.width(if (tiny) 6.dp else 9.dp))
            if (tiny) {
                Text(block.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(block.title, fontWeight = FontWeight.SemiBold, color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                if (compact) "${Planner.label(block.startAt)} 至 ${Planner.label(block.endAt)} · ${task?.subject ?: "计划"}"
                else "${Planner.label(block.startAt)} 至 ${Planner.label(block.endAt)} · ${task?.subject ?: "计划"} · ${Priority.fromRank(block.priority).label}优先",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        maxLines = if (compact) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (completed) Icon(Icons.Default.Check, "已完成", Modifier.size(18.dp), tint = accent)
        }
    }
}

@Composable
private fun ExclusionBrick(exclusion: TimelineExclusionBlock, height: Dp) {
    val tiny = height < 54.dp
    Surface(
        modifier = Modifier.fillMaxWidth().height(height),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = RoundedCornerShape(5.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f))
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (tiny) 7.dp else 11.dp, vertical = if (tiny) 2.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)))
            Spacer(Modifier.width(8.dp))
            if (tiny) {
                Text(exclusion.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(exclusion.title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${Planner.label(exclusion.startAt)} 至 ${Planner.label(exclusion.endAt)} · 排除时段", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun MultiDayTimeline(
    start: LocalDate,
    days: Int,
    allBlocks: List<ScheduleBlockEntity>,
    taskById: Map<Long, TaskEntity>,
    onDayClick: (LocalDate) -> Unit
) {
    Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(days) { offset ->
            val date = start.plusDays(offset.toLong())
            val dayBlocks = blocksForPlanningDay(allBlocks, date)
            val isToday = date == currentPlanningDate()
            val todayStyle = if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)) else Modifier
            Column(Modifier.width(if (days == 3) 210.dp else 154.dp).fillMaxHeight().then(todayStyle).clickable { onDayClick(date) }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${date.monthValue}/${date.dayOfMonth}  ${weekday(date)}", fontWeight = FontWeight.SemiBold)
                    if (isToday) {
                        Spacer(Modifier.width(6.dp))
                        Text("今日", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider()
                if (dayBlocks.isEmpty()) Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { Text("无安排", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else dayBlocks.forEach { block ->
                    Box(Modifier.padding(vertical = 4.dp)) { ScheduleBrick(block, block.taskId?.let(taskById::get), 96.dp, null) }
                }
            }
        }
    }
}

@Composable
private fun MonthTimeline(
    selectedDate: LocalDate,
    allBlocks: List<ScheduleBlockEntity>,
    taskById: Map<Long, TaskEntity>,
    onDayClick: (LocalDate) -> Unit
) {
    val month = YearMonth.from(selectedDate)
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value - 1
    val cells: List<LocalDate?> = List(leading) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    val padded = cells + List((7 - cells.size % 7) % 7) { null }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f).padding(4.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium) } }
        padded.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                week.forEach { date ->
                    if (date == null) Spacer(Modifier.weight(1f).fillMaxHeight())
                    else {
                        val dayBlocks = blocksForCalendarDay(allBlocks, date)
                        val isToday = date == currentPlanningDate()
                        val dayStyle = if (isToday) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                        } else {
                            Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().then(dayStyle).clickable { onDayClick(date) }.padding(4.dp)
                        ) {
                            Text(if (isToday) "${date.dayOfMonth} 今日" else date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium, color = if (isToday) MaterialTheme.colorScheme.primary else Color.Unspecified, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                            val markers = dayBlocks.map { block ->
                                val task = block.taskId?.let(taskById::get)
                                if (task?.completed == true) MaterialTheme.colorScheme.outline else typeStyle(task?.subject ?: "计划").accent
                            }
                            markers.take(3).forEach { markerColor ->
                                Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp).clip(RoundedCornerShape(2.dp)).background(markerColor))
                            }
                            if (markers.size > 3) Text("+${markers.size - 3}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

private fun blocksForPlanningDay(blocks: List<ScheduleBlockEntity>, date: LocalDate): List<ScheduleBlockEntity> {
    val start = atMillis(date, PLANNING_DAY_START_MINUTE)
    val end = atMillis(date, PLANNING_DAY_END_MINUTE)
    return blocks.filter { it.startAt < end && it.endAt > start }.sortedBy { it.startAt }
}

private fun exclusionsForPlanningDay(exclusions: List<ExcludedTime>, date: LocalDate): List<TimelineExclusionBlock> = exclusions
    .asSequence()
    .filter { it.enabled }
    .mapNotNull { period ->
        val startMinute = period.startMinute.coerceIn(PLANNING_DAY_START_MINUTE, PLANNING_DAY_END_MINUTE - 10)
        val endMinute = period.endMinute.coerceIn(startMinute + 10, PLANNING_DAY_END_MINUTE)
        if (endMinute <= startMinute) null else TimelineExclusionBlock(
            id = "${date.toEpochDay()}-${period.id}",
            title = period.label,
            startAt = atMillis(date, startMinute),
            endAt = atMillis(date, endMinute)
        )
    }
    .sortedBy { it.startAt }
    .toList()

private fun blocksForCalendarDay(blocks: List<ScheduleBlockEntity>, date: LocalDate): List<ScheduleBlockEntity> {
    val start = atMillis(date, 0)
    val end = atMillis(date, 24 * 60)
    return blocks.filter { it.startAt < end && it.endAt > start }.sortedBy { it.startAt }
}

private fun atMillis(date: LocalDate, minuteOfDay: Int): Long {
    val dayOffset = Math.floorDiv(minuteOfDay, 24 * 60)
    val clockMinute = Math.floorMod(minuteOfDay, 24 * 60)
    return LocalDateTime.of(date.plusDays(dayOffset.toLong()), LocalTime.of(clockMinute / 60, clockMinute % 60))
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

private fun weekday(date: LocalDate) = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[date.dayOfWeek.value - 1]

private fun currentPlanningDate(): LocalDate = if (LocalTime.now().isBefore(LocalTime.of(2, 0))) {
    LocalDate.now().minusDays(1)
} else {
    LocalDate.now()
}

private fun timelineTitle(mode: TimelineMode, date: LocalDate): String = when (mode) {
    TimelineMode.DAY -> "${date.monthValue}月${date.dayOfMonth}日  ${weekday(date)}"
    TimelineMode.THREE_DAY -> "${date.monthValue}月${date.dayOfMonth}日 至 ${date.plusDays(2).monthValue}月${date.plusDays(2).dayOfMonth}日"
    TimelineMode.WEEK -> {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        "${monday.monthValue}月${monday.dayOfMonth}日 至 ${monday.plusDays(6).monthValue}月${monday.plusDays(6).dayOfMonth}日"
    }
    TimelineMode.MONTH -> "${date.year}年${date.monthValue}月"
}

private fun shiftDate(mode: TimelineMode, date: LocalDate, direction: Long): LocalDate = when (mode) {
    TimelineMode.DAY -> date.plusDays(direction)
    TimelineMode.THREE_DAY -> date.plusDays(direction)
    TimelineMode.WEEK -> date.plusWeeks(direction)
    TimelineMode.MONTH -> date.plusMonths(direction)
}
