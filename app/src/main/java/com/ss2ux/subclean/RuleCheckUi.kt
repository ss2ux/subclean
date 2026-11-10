package com.ss2ux.subclean

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 규칙 탭 점검 줄에 쓰는 한 줄 요약. 아직 안 했으면 안내 문구 */
fun checkSummary(findings: List<Finding>?, stale: Boolean, checking: Boolean): String {
    if (checking) return "점검 중이에요…"
    if (findings == null) return "규칙끼리·제외 단어와 부딪히는지 점검해 보세요"
    val e = findings.count { it.severity == Severity.ERROR }
    val w = findings.count { it.severity == Severity.WARN }
    val i = findings.count { it.severity == Severity.INFO }
    val base = if (e + w + i == 0) "충돌 없음" else "오류 $e · 경고 $w · 참고 $i"
    return if (stale) "$base (규칙이 바뀌었어요. 다시 점검해 주세요)" else base
}

private fun speechText(f: Finding): String {
    if (f.speeches.isEmpty() || f.speeches.size >= Speech.values().size) return "모든 말투에서"
    return f.speeches.joinToString(", ") { it.ruleLabel } + " 선택 시"
}

@Composable
private fun severityColor(s: Severity): Color = when (s) {
    Severity.ERROR -> MaterialTheme.colorScheme.errorContainer
    Severity.WARN -> MaterialTheme.colorScheme.tertiaryContainer
    Severity.INFO -> MaterialTheme.colorScheme.surfaceVariant
}

/** 문제 한 건. 관련 규칙을 누르면 그 규칙을 고치러 간다 */
@Composable
fun FindingCard(f: Finding, rules: List<Rule>, onEditRule: ((Rule) -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = severityColor(f.severity))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "[${f.severity.label}] ${f.title}",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(speechText(f), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(f.detail, fontSize = 13.sp)
            if (f.words.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("제외 단어: " + f.words.joinToString(", "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onEditRule != null) {
                for (id in f.ruleIds.take(4)) {
                    val r = rules.firstOrNull { it.id == id } ?: continue
                    TextButton(onClick = { onEditRule(r) }) {
                        Text(
                            "규칙 고치기: ${r.find} → ${r.replace.ifEmpty { "(삭제)" }}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/** 규칙 탭의 '규칙 점검' 결과 창 */
@Composable
fun RuleCheckDialog(
    findings: List<Finding>?,
    checking: Boolean,
    stale: Boolean,
    rules: List<Rule>,
    onRecheck: () -> Unit,
    onEditRule: (Rule) -> Unit,
    onClose: () -> Unit
) {
    var showInfo by remember { mutableStateOf(false) }
    val important = findings.orEmpty().filter { it.severity != Severity.INFO }
    val infos = findings.orEmpty().filter { it.severity == Severity.INFO }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("규칙 충돌 점검", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "닫기") }
                }
                Text(
                    checkSummary(findings, stale, checking),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onRecheck, enabled = !checking) { Text(if (checking) "점검 중…" else "다시 점검") }
                    if (checking) CircularProgressIndicator(modifier = Modifier.width(24.dp).height(24.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "켜진 규칙만 봐요. 말투(공통/높임1/높임2/반말)마다 같이 적용되는 규칙이 다르니 말투별로 따로 살펴요. 규칙은 바꾸지 않아요.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (findings == null) {
                        Text("점검 결과를 불러오는 중이에요", modifier = Modifier.align(Alignment.Center))
                    } else if (findings.isEmpty() && !checking) {
                        Text(
                            "부딪히는 규칙이 없어요 👍",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(important, key = { it.key }) { f -> FindingCard(f, rules, onEditRule) }
                            if (infos.isNotEmpty()) {
                                item(key = "info-toggle") {
                                    OutlinedButton(onClick = { showInfo = !showInfo }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (showInfo) "참고 ${infos.size}건 접기" else "참고 ${infos.size}건 보기 (의도한 연쇄일 수 있어요)")
                                    }
                                }
                                if (showInfo) {
                                    items(infos, key = { it.key }) { f -> FindingCard(f, rules, onEditRule) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 규칙을 저장하려는데 문제가 보일 때 / 제외 단어를 넣으려는데 규칙을 가릴 때 띄우는 경고창 */
@Composable
fun FindingsWarningDialog(
    title: String,
    findings: List<Finding>,
    forceLabel: String,
    onBack: () -> Unit,
    onForce: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in findings.take(3)) FindingCard(f, emptyList(), null)
                if (findings.size > 3) {
                    Text("…외 ${findings.size - 3}건 (규칙 탭의 '규칙 점검'에서 전부 볼 수 있어요)", fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onForce) { Text(forceLabel) } },
        dismissButton = { TextButton(onClick = onBack) { Text("돌아가서 고치기") } }
    )
}
