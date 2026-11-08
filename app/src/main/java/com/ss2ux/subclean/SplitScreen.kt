package com.ss2ux.subclean

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private fun Int.fmt(): String = "%,d".format(this)

/**
 * 분할 탭: 5천자가 넘는 텍스트를 문장 단위로 나눠서 조각마다 복사한다 (DeepL 에 하나씩 붙여 넣기용).
 * 인터넷은 쓰지 않는다. 나누는 규칙은 Splitter.kt.
 */
@Composable
fun SplitScreen(vm: AppViewModel) {
    val clipboard = LocalClipboardManager.current
    val openLauncher = rememberLauncherForActivityResult(
        OpenDocumentRw { vm.lastOpenUri }
    ) { uri ->
        if (uri != null) vm.loadSplitFile(uri)
    }
    val chunks = vm.splitChunks
    val copied = vm.splitCopied
    // 다음에 복사할 조각 = 아직 복사하지 않은 첫 조각
    val next = (0 until chunks.size).firstOrNull { it !in copied }

    fun copy(i: Int) {
        if (i !in chunks.indices) return
        clipboard.setText(AnnotatedString(chunks[i]))
        vm.markSplitCopied(i)
    }

    // 슬라이더는 끄는 동안 값만 바꾸고, 손을 떼면 다시 나눈다
    var sliderValue by remember(vm.splitLimit) { mutableStateOf(vm.splitLimit.toFloat()) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { openLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("파일 열기", maxLines = 1)
            }
            OutlinedButton(onClick = { vm.importOpenSubtitleToSplit() }, modifier = Modifier.weight(1f)) {
                Text("열린 자막 가져오기", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.updateSplitText("") }, enabled = vm.splitText.isNotEmpty()) {
                Icon(Icons.Default.Delete, contentDescription = "전체 지우기")
            }
        }

        OutlinedTextField(
            value = vm.splitText,
            onValueChange = { vm.updateSplitText(it) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(150.dp),
            placeholder = { Text("DeepL에 넣을 긴 텍스트를 붙여 넣거나, 파일을 여세요") }
        )

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("글자 수 제한", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("${sliderValue.roundToInt().fmt()}자", fontSize = 13.sp)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { vm.updateSplitLimit(sliderValue.roundToInt()) },
            valueRange = 1000f..5000f,
            steps = 39   // 100자 단위
        )

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "입력 ${vm.splitText.length.fmt()}자 · 조각 ${chunks.size}개",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            if (chunks.isNotEmpty()) {
                Text("복사 ${copied.size}/${chunks.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (chunks.isNotEmpty()) {
            LinearProgressIndicator(
                progress = copied.size.toFloat() / chunks.size,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
        }

        if (chunks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "텍스트를 넣으면\n자동으로 나뉘어요",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            if (next != null) {
                Button(onClick = { copy(next) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("다음 조각 복사 (${next + 1}/${chunks.size})")
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("모두 복사했어요", color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.resetSplitCopied() }) { Text("복사 표시 지우기") }
                }
            }
            if (chunks.size == 1) {
                Text(
                    "제한 안에 들어가서 나눌 필요가 없어요.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(count = chunks.size, key = { it }) { i ->
                    val text = chunks[i]
                    val done = i in copied
                    val isNext = i == next
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = if (isNext) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = CardDefaults.cardColors(
                            containerColor = if (done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "조각 #${i + 1}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "  ${text.length.fmt()}자",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(onClick = { copy(i) }) {
                                    Icon(
                                        if (done) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (done) "복사됨" else "복사")
                                }
                            }
                            Text(
                                text,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
