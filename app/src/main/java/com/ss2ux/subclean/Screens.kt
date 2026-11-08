package com.ss2ux.subclean

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

/** 분할 탭이 필요한 글자 수 기준 (DeepL 무료 한도) */
private const val SPLIT_LIMIT_CHARS = 5000

@Composable
fun AppScreen(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(vm.message) {
        val m = vm.message
        if (m != null) {
            snack.showSnackbar(m)
            vm.clearMessage()
        }
    }
    // 저장·복사 요청이 오면 클립보드에 넣고 비운다
    LaunchedEffect(vm.pendingClipboard) {
        val t = vm.pendingClipboard
        if (t != null) {
            try {
                clipboard.setText(AnnotatedString(t))
            } catch (_: Exception) {
                // 너무 크면 실패할 수 있음 — 메시지로 이미 안내됨
            }
            vm.clearClipboard()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0, onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    label = { Text("다듬기") }
                )
                NavigationBarItem(
                    selected = tab == 1, onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Edit, contentDescription = null) },
                    label = { Text("규칙 ${vm.rules.size}") }
                )
                NavigationBarItem(
                    selected = tab == 2, onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.ContentCut, contentDescription = null) },
                    label = { Text("분할") }
                )
                NavigationBarItem(
                    selected = tab == 3, onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("설정") }
                )
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> CleanScreen(vm)
                1 -> RulesScreen(vm)
                2 -> SplitScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

// =====================================================================
// 다듬기 탭: 파일 열기 -> 전/후 미리보기 -> 저장
// =====================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CleanScreen(vm: AppViewModel) {
    // 최근 연 파일 위치를 파일 선택기 시작점으로 사용
    val openLauncher = rememberLauncherForActivityResult(
        OpenDocumentRw { vm.lastOpenUri }
    ) { uri ->
        if (uri != null) vm.openFile(uri)
    }
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.save(uri, false)
    }
    var onlyChanged by rememberSaveable { mutableStateOf(true) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    val pullState = rememberPullToRefreshState()
    if (pullState.isRefreshing) {
        LaunchedEffect(true) { vm.reloadFile { pullState.endRefresh() } }
    }

    val doc = vm.doc
    val dec = vm.decoded
    val pv = vm.preview

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { openLauncher.launch(arrayOf("*/*")) },
                enabled = !vm.busy
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("파일 열기")
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    vm.fileName ?: "자막 파일(.srt .vtt .lrc .txt)을 열어 주세요",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 최근 연 파일 위치 안내 (지금 연 파일과 다를 때만)
                val last = vm.lastOpenName
                if (last != null && last != vm.fileName) {
                    Text(
                        "최근: $last",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (last != null && vm.fileName == null) {
                    Text(
                        "최근: $last 위치에서 열어요",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        SpeechRow(vm)

        if (doc != null && dec != null) {
            EncodingRow(vm, doc, dec)

            if (pv != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "다듬을 수 있는 ${doc.editableCount}줄 중 ${pv.changedCount}줄이 바뀌어요" +
                            (if (pv.excludedCount > 0) " (제외 ${pv.excludedCount}줄)" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (pv.excludedCount > 0) {
                        TextButton(onClick = { vm.clearExcluded() }) { Text("제외 취소") }
                    }
                }
                // 결과 글자 수: 5천자를 넘으면 분할 탭이 필요하다
                val over = pv.totalChars > SPLIT_LIMIT_CHARS
                Text(
                    "결과 전체 ${"%,d".format(pv.totalChars)}자 (대사만 ${"%,d".format(pv.dialogueChars)}자) · " +
                        if (over) "5천자 초과, 분할 필요" else "5천자 이하, 분할 불필요",
                    fontSize = 13.sp,
                    color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
                for (p in pv.problems) {
                    Text(p, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { saveAsLauncher.launch(suggestName(vm.fileName)) },
                        enabled = !vm.busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("다른 이름으로 저장") }
                    Button(
                        onClick = { confirmOverwrite = true },
                        enabled = !vm.busy && pv.changedCount > 0,
                        modifier = Modifier.weight(1f)
                    ) { Text("원본에 덮어쓰기") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = onlyChanged,
                        onClick = { onlyChanged = !onlyChanged },
                        label = { Text("바뀐 줄만 보기") }
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.copyResult() }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("복사")
                    }
                    TextButton(onClick = { vm.showResult() }) { Text("결과 전체 보기") }
                }

                val shown = remember(pv, onlyChanged) {
                    if (onlyChanged) pv.rows.filter { it.changed } else pv.rows
                }
                val listState = rememberLazyListState()
                Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(pullState.nestedScrollConnection)) {
                    Row(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState) {
                            if (shown.isEmpty()) {
                                item {
                                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            if (pv.rows.isEmpty()) "다듬을 대사가 없어요" else "바뀌는 줄이 없어요",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            } else {
                                items(shown, key = { it.index }) { row -> RowCard(row) { vm.toggleExclude(row.index) } }
                            }
                        }
                        ListScrollbar(listState)
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("다듬는 중...")
                }
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "규칙 탭에서 치환 목록을 만들고,\n파일을 열어 바뀌는 모습을 확인한 뒤 저장하세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // 결과 전체 보기 (수동). 저장 후에는 자동으로 띄우지 않고 클립보드에만 넣는다.
    vm.resultText?.let { text -> ResultDialog(text) { vm.closeResult() } }

    // 덮어쓰기 확인 — 저장 직후 결과 창이 더 이상 안 뜨므로 가려지지 않음
    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("원본에 덮어쓸까요?") },
            text = { Text("원본 파일이 다듬은 내용으로 바뀌고 되돌릴 수 없어요. 안전하게 하려면 '다른 이름으로 저장'을 쓰세요.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOverwrite = false
                    vm.save(null, true)
                }) { Text("덮어쓰기") }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("취소") } }
        )
    }

    // 연쇄 치환(A→B→C)은 Cleaner 가 최대 10패스까지 자동 처리한다.
}

@Composable
private fun ListScrollbar(state: LazyListState) {
    val total = state.layoutInfo.totalItemsCount
    val visible = state.layoutInfo.visibleItemsInfo.size
    if (total < 20 || visible < 1 || visible >= total) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableStateOf(0f) }
    val thumbH = 56.dp

    BoxWithConstraints(Modifier.fillMaxHeight().width(24.dp)) {
        val heightPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { thumbH.toPx() }
        val span = (heightPx - thumbPx).coerceAtLeast(1f)
        val frac = if (dragging) dragFrac
        else state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
        val y = (frac.coerceIn(0f, 1f) * span).roundToInt()
        val jumpIndex = (dragFrac.coerceIn(0f, 1f) * (total - 1)).roundToInt()

        Box(
            Modifier.fillMaxSize().pointerInput(total, heightPx) {
                detectVerticalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        dragFrac = ((o.y - thumbPx / 2) / span).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFrac * (total - 1)).roundToInt()) }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        dragFrac = ((change.position.y - thumbPx / 2) / span).coerceIn(0f, 1f)
                        scope.launch { state.scrollToItem((dragFrac * (total - 1)).roundToInt()) }
                    }
                )
            }
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, y) }
                .width(if (dragging) 10.dp else 5.dp)
                .height(thumbH)
                .background(
                    MaterialTheme.colorScheme.outline.copy(alpha = if (dragging) 0.9f else 0.6f),
                    RoundedCornerShape(50)
                )
        )
        if (dragging) {
            Surface(
                Modifier.align(Alignment.TopEnd).offset { IntOffset((-32).dp.roundToPx(), y + 8) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.inverseSurface
            ) {
                Text(
                    "${jumpIndex + 1} / $total",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun SpeechRow(vm: AppViewModel) {
    val cur = vm.settings.speech
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("말투", style = MaterialTheme.typography.bodyMedium)
            for (s in Speech.values()) {
                FilterChip(
                    selected = cur == s,
                    onClick = { vm.updateSettings(vm.settings.copy(speech = s)) },
                    label = { Text(s.chipLabel) }
                )
            }
        }
        Text(
            when (cur) {
                Speech.ALL -> "공통 규칙만 적용해요"
                Speech.HONORIFIC1 -> "공통 + 높임1: 화자 연상 · 청자 연하 (존대 표현)"
                Speech.HONORIFIC2 -> "공통 + 높임2: 화자 연하 · 청자 연상 (해요체 존댓말)"
                Speech.CASUAL -> "공통 + 반말 규칙: 반말(해체)로 통일해요"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (cur != Speech.ALL && vm.rules.none { it.speech == cur }) {
            Text(
                "이 말투의 규칙이 하나도 없어요.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ResultDialog(text: String, onClose: () -> Unit) {
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var copyError by remember { mutableStateOf(false) }
    val limit = 80_000
    val truncated = text.length > limit
    val shown = if (truncated) text.substring(0, limit) else text

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("바뀐 전체 텍스트", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "닫기") }
                }
                Button(
                    onClick = {
                        try {
                            clip.setText(AnnotatedString(text))
                            copied = true
                            copyError = false
                        } catch (e: Exception) {
                            copyError = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "복사했어요 (전체 ${text.length}자)" else "전체 복사")
                }
                if (copyError) {
                    Text("복사하지 못했어요. 파일이 너무 클 수 있어요", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                if (truncated) {
                    Text(
                        "파일이 커서 앞부분 ${limit}자만 보여요. '전체 복사'는 전체를 복사해요.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(shown, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

private fun suggestName(name: String?): String {
    val n = name ?: "subtitle.txt"
    val dot = n.lastIndexOf('.')
    return if (dot > 0) n.substring(0, dot) + "_clean" + n.substring(dot) else n + "_clean"
}

@Composable
private fun EncodingRow(vm: AppViewModel, doc: Doc, dec: Decoded) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${doc.kind.label} · ${Encodings.label(dec.charset)}" + (if (dec.auto) " (자동 감지)" else ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Box {
            TextButton(onClick = { menu = true }) { Text("인코딩 변경") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("자동 감지") },
                    onClick = { vm.setEncoding(null); menu = false }
                )
                for (o in Encodings.options) {
                    DropdownMenuItem(
                        text = { Text(o.label) },
                        onClick = { vm.setEncoding(o.charset); menu = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowCard(row: PreviewRow, onToggle: () -> Unit) {
    val tappable = row.changed || row.excluded
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).let { if (tappable) it.clickable(onClick = onToggle) else it }
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "줄 ${row.index + 1}" + (if (row.excluded) " · 바꾸지 않음 (누르면 다시 포함)" else if (row.changed) " · 누르면 제외" else ""),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (row.excluded) {
                Text(row.before)
                Text(
                    row.after.ifEmpty { "(빈 줄)" },
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (row.changed) {
                Text(
                    row.before,
                    textDecoration = TextDecoration.LineThrough,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(row.after.ifEmpty { "(빈 줄)" }, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(row.before)
            }
        }
    }
}

// =====================================================================
// 규칙 탭: 치환 목록 관리
// =====================================================================

@Composable
private fun RulesScreen(vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rule?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rule?>(null) }
    // 0 전체, 1 공통, 2 높임1, 3 높임2, 4 반말, 5 정규식만
    var filterIdx by rememberSaveable { mutableIntStateOf(0) }

    var exportMenuExpanded by remember { mutableStateOf(false) }
    var exportRegexOnly by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importRules(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) vm.exportRules(uri, regexOnly = exportRegexOnly)
    }

    val q = query.trim()
    val sort = vm.ruleSort
    val sortNames = listOf("최신순(등록 내림차순)", "오래된순(등록 오름차순)", "가나다순")
    val listState = rememberLazyListState()
    var prevSize by remember { mutableIntStateOf(vm.rules.size) }
    
    LaunchedEffect(vm.rules.size) {
        if (vm.rules.size > prevSize && sort == 0) listState.scrollToItem(0)
        prevSize = vm.rules.size
    }
    val shown = remember(vm.rules, q, filterIdx, sort) {
        val byFilter = when (filterIdx) {
            1 -> vm.rules.filter { it.speech == Speech.ALL }
            2 -> vm.rules.filter { it.speech == Speech.HONORIFIC1 }
            3 -> vm.rules.filter { it.speech == Speech.HONORIFIC2 }
            4 -> vm.rules.filter { it.speech == Speech.CASUAL }
            5 -> vm.rules.filter { it.regex }
            else -> vm.rules
        }
        val found = if (q.isEmpty()) byFilter
        else byFilter.filter { it.find.contains(q, ignoreCase = true) || it.replace.contains(q, ignoreCase = true) }
        
        when (sort) {
            0 -> found.sortedByDescending { it.id }
            2 -> found.sortedBy { it.find.lowercase() }
            else -> found
        }
    }
    val defaultSpeech = when (filterIdx) {
        2 -> Speech.HONORIFIC1
        3 -> Speech.HONORIFIC2
        4 -> Speech.CASUAL
        else -> Speech.ALL
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("규칙 검색") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { adding = true }) { Text("규칙 추가") }
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("가져오기") }
            
            Box {
                TextButton(onClick = { exportMenuExpanded = true }) { Text("내보내기") }
                DropdownMenu(
                    expanded = exportMenuExpanded,
                    onDismissRequest = { exportMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("전체 내보내기") },
                        onClick = {
                            exportMenuExpanded = false
                            exportRegexOnly = false
                            exportLauncher.launch("치환규칙.tsv")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("정규식만 내보내기") },
                        onClick = {
                            exportMenuExpanded = false
                            exportRegexOnly = true
                            exportLauncher.launch("정규식규칙.tsv")
                        }
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val names = listOf("전체", "공통", "높임1", "높임2", "반말", "정규식만")
            for ((i, nm) in names.withIndex()) {
                FilterChip(
                    selected = filterIdx == i,
                    onClick = { filterIdx = i },
                    label = { Text(nm, maxLines = 1, softWrap = false) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "규칙 ${vm.rules.size}개 (켜짐 ${vm.rules.count { it.enabled }}개)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            var sortMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { sortMenu = true }) { Text("정렬: ${sortNames[sort].substringBefore("(")}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    for ((i, nm) in sortNames.withIndex()) {
                        DropdownMenuItem(
                            text = { Text(nm) },
                            onClick = { vm.changeRuleSort(i); sortMenu = false }
                        )
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.rules.isEmpty()) "아직 규칙이 없어요" else "검색 결과가 없어요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState) {
                    items(shown, key = { it.id }) { r ->
                        Row(
                            Modifier.fillMaxWidth().clickable { editing = r }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Switch(checked = r.enabled, onCheckedChange = { vm.updateRule(r.copy(enabled = it)) })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    r.find + "  →  " + r.replace.ifEmpty { "(삭제)" },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (r.regex || r.speech != Speech.ALL) {
                                    Text(
                                        listOfNotNull(
                                            if (r.regex) "정규식" else null,
                                            if (r.speech != Speech.ALL) r.speech.ruleLabel else null
                                        ).joinToString(" · "),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(onClick = { deleting = r }) {
                                Icon(Icons.Default.Delete, contentDescription = "삭제")
                            }
                        }
                    }
                }
                ListScrollbar(listState)
            }
        }
    }

    if (adding) {
        RuleDialog(
            initial = null,
            defaultSpeech = defaultSpeech,
            onDismiss = { adding = false },
            onSave = { f, r, rx, sp -> vm.addRule(f, r, rx, sp); adding = false }
        )
    }
    editing?.let { cur ->
        RuleDialog(
            initial = cur,
            defaultSpeech = cur.speech,
            onDismiss = { editing = null },
            onSave = { f, r, rx, sp -> vm.updateRule(cur.copy(find = f, replace = r, regex = rx, speech = sp)); editing = null }
        )
    }
    deleting?.let { cur ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("규칙을 지울까요?") },
            text = { Text(cur.find + "  →  " + cur.replace.ifEmpty { "(삭제)" }) },
            confirmButton = {
                TextButton(onClick = { vm.deleteRule(cur.id); deleting = null }) { Text("지우기") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }
        )
    }
}

@Composable
private fun RuleDialog(
    initial: Rule?,
    defaultSpeech: Speech,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean, Speech) -> Unit
) {
    var find by remember { mutableStateOf(initial?.find ?: "") }
    var replace by remember { mutableStateOf(initial?.replace ?: "") }
    var regex by remember { mutableStateOf(initial?.regex ?: false) }
    var speech by remember { mutableStateOf(initial?.speech ?: defaultSpeech) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "규칙 추가" else "규칙 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = find, onValueChange = { find = it },
                    label = { Text("찾을 말") }, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = replace, onValueChange = { replace = it },
                    label = { Text("바꿀 말 (비우면 삭제)") }, modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = regex, onCheckedChange = { regex = it })
                    Text("정규식으로 처리")
                }
                if (regex) {
                    Text(
                        "바꿀 말에서 $1 처럼 괄호 묶음을 다시 쓸 수 있어요. " +
                            "찾을 말에는 {받침} {무받침} {ㄹ받침} {ㄴ받침} {ㅆ받침} {양성받침} 으로 받침 있는/없는 글자를 가리킬 수 있어요. 예: (?<={받침})이야",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("말투", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in Speech.values()) {
                        FilterChip(selected = speech == s, onClick = { speech = s }, label = { Text(s.ruleLabel) })
                    }
                }
                Text(
                    "공통은 어느 말투에서나 적용돼요. 높임1·높임2·반말은 그 말투를 골랐을 때만 적용돼요.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(find, replace, regex, speech) }, enabled = find.isNotEmpty()) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

// =====================================================================
// 설정 탭: 반복 압축 등
// =====================================================================

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val o = vm.settings
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("반복 압축", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("같은 글자·단어가 ${o.threshold}번 이상 이어지면 압축해요")
        Slider(
            value = o.threshold.toFloat(),
            onValueChange = { vm.updateSettings(o.copy(threshold = it.roundToInt())) },
            valueRange = 3f..10f,
            steps = 6
        )
        val keepMax = o.threshold - 1
        if (keepMax > 1) {
            Text("압축하고 ${o.keep.coerceIn(1, keepMax)}개만 남겨요")
            Slider(
                value = o.keep.coerceIn(1, keepMax).toFloat(),
                onValueChange = { vm.updateSettings(o.copy(keep = it.roundToInt())) },
                valueRange = 1f..keepMax.toFloat(),
                steps = keepMax - 2
            )
        } else {
            Text("압축하고 1개만 남겨요")
        }
        Spacer(Modifier.height(8.dp))
        SettingSwitch(
            "한 글자·기호 반복", "ㅋㅋㅋㅋㅋㅋ → ㅋㅋ, ?????? → ??, 哈哈哈哈哈 → 哈哈 (숫자는 건드리지 않아요)",
            o.compressChars
        ) { vm.updateSettings(o.copy(compressChars = it)) }
        SettingSwitch(
            "단어 반복", "응 응 응 응 응 → 응 응 (띄어쓰기로 구분되는 단어)",
            o.compressWords
        ) { vm.updateSettings(o.copy(compressWords = it)) }
        SettingSwitch(
            "여러 글자 덩어리 반복 (실험)", "너무너무너무너무너무 → 너무너무. 띄어쓰기 없는 중국어·일본어용. 엉뚱하게 줄어드는지 미리보기로 확인하세요",
            o.compressUnits
        ) { vm.updateSettings(o.copy(compressUnits = it)) }

        Spacer(Modifier.height(16.dp))
        Text("조사", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            "치환 뒤 조사 보정", "철수가 → 민철로 바꾸면 민철이, 영희는 → 민수로 바꾸면 민수는. 일반 규칙으로 바뀐 말 바로 뒤의 이/가 은/는 을/를 과/와 (으)로 (이)랑 (이)나 (이)라 아/야 예요/이에요만 고쳐요. 정규식 규칙은 대상이 아니에요",
            o.fixJosa
        ) { vm.updateSettings(o.copy(fixJosa = it)) }

        Spacer(Modifier.height(16.dp))
        Text("글자 정리", style = MaterialTheme.typography.titleMedium)
        SettingSwitch(
            "유니코드 정규화 (NFC)", "분리된 한글 자모·일본어 탁점 등을 하나로 합쳐서 치환 규칙이 빠짐없이 맞게 해요",
            o.normalize
        ) { vm.updateSettings(o.copy(normalize = it)) }

        Spacer(Modifier.height(16.dp))
        Text("제외 단어", style = MaterialTheme.typography.titleMedium)
        Text(
            "여기 넣은 말은 어떤 규칙(일반·정규식·말투·조사 보정)과 반복 압축에도 바뀌지 않아요. 단어 안에 들어 있는 부분도 지켜요. 여러 개는 쉼표(,)로 구분해서 한 번에 넣을 수 있어요.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var protectInput by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = protectInput,
                onValueChange = { protectInput = it },
                label = { Text("제외할 단어") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val add = protectInput.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (add.isNotEmpty()) {
                    vm.updateSettings(o.copy(protect = (o.protect + add).distinct()))
                    protectInput = ""
                }
            }) { Text("추가") }
        }
        if (o.protect.isEmpty()) {
            Text(
                "제외 단어가 없어요",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        for (w in o.protect) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(w, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.updateSettings(o.copy(protect = o.protect - w)) }) {
                    Icon(Icons.Default.Close, contentDescription = "지우기")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "이 앱은 인터넷 권한이 없어 모든 처리가 기기 안에서만 이루어져요.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
