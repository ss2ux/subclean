package com.ss2ux.subclean

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

/** 미리보기 한 줄: 다듬기 대상 줄의 전/후 */
class PreviewRow(val index: Int, val before: String, val after: String) {
    val changed: Boolean get() = before != after
}

class Preview(
    val newBodies: List<String>,   // 모든 줄의 새 본문 (편집 대상이 아닌 줄은 원래 그대로)
    val rows: List<PreviewRow>,    // 편집 대상 줄만
    val changedCount: Int,
    val problems: List<String>
)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val ruleStore = RuleStore(app)
    private val settingsStore = SettingsStore(app)

    var rules by mutableStateOf(ruleStore.load())
        private set
    var settings by mutableStateOf(settingsStore.load())
        private set

    // 열린 파일
    var fileName by mutableStateOf<String?>(null)
        private set
    var fileUri by mutableStateOf<Uri?>(null)
        private set
    var forcedCharset by mutableStateOf<Charset?>(null)   // null = 자동 감지
        private set
    var decoded by mutableStateOf<Decoded?>(null)
        private set
    var doc by mutableStateOf<Doc?>(null)
        private set
    var preview by mutableStateOf<Preview?>(null)
        private set

    // 저장 후(또는 '결과 전체 보기')에 보여 주는 바뀐 전체 텍스트. null 이면 창이 닫혀 있음
    var resultText by mutableStateOf<String?>(null)
        private set

    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    private var bytes: ByteArray? = null
    private var job: Job? = null

    fun clearMessage() { message = null }

    fun closeResult() { resultText = null }

    /** 지금 미리보기 기준으로 바뀐 전체 텍스트를 만들어 보여 준다 (저장 안 해도 됨) */
    fun showResult() {
        val dc = doc ?: return
        val p = preview ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val text = dc.render(p.newBodies)
            withContext(Dispatchers.Main) { resultText = text }
        }
    }

    // ---------------- 파일 ----------------

    fun openFile(uri: Uri) {
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 덮어쓰기를 위해 쓰기 권한을 유지해 둔다 (실패해도 열기는 계속)
                try {
                    app.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (e: Exception) {
                }
                var name = "자막"
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val data = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (data == null) {
                    withContext(Dispatchers.Main) { message = "파일을 열 수 없어요" }
                } else if (data.size > 20_000_000) {
                    withContext(Dispatchers.Main) { message = "파일이 너무 커요 (20MB 초과)" }
                } else {
                    withContext(Dispatchers.Main) {
                        bytes = data
                        fileUri = uri
                        fileName = name
                        forcedCharset = null
                        reparse()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "열기 실패: ${e.message ?: e}" }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
    }

    fun setEncoding(cs: Charset?) {
        forcedCharset = cs
        reparse()
    }

    private fun reparse() {
        val b = bytes
        val n = fileName
        if (b == null || n == null) return
        val d = Encodings.decode(b, forcedCharset)
        decoded = d
        doc = Doc.parse(n, d.text)
        recompute()
    }

    // ---------------- 다듬기 미리보기 ----------------

    fun recompute() {
        val d = doc
        if (d == null) {
            preview = null
            return
        }
        val r = rules
        val s = settings
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.Default) {
            val cleaner = Cleaner(r, s)
            val bodies = ArrayList<String>(d.lines.size)
            val rows = ArrayList<PreviewRow>()
            var changed = 0
            for ((i, ln) in d.lines.withIndex()) {
                if (!ln.editable) {
                    bodies.add(ln.body)
                } else {
                    val after = cleaner.clean(ln.body)
                    bodies.add(after)
                    rows.add(PreviewRow(i, ln.body, after))
                    if (after != ln.body) changed++
                }
            }
            val p = Preview(bodies, rows, changed, cleaner.problems.distinct())
            withContext(Dispatchers.Main) { preview = p }
        }
    }

    // ---------------- 저장 ----------------

    /** overwrite = true 면 열었던 원본 파일에 덮어쓰고, false 면 target 에 새로 저장 */
    fun save(target: Uri?, overwrite: Boolean) {
        val d = decoded ?: return
        val dc = doc ?: return
        val p = preview ?: return
        val uri = if (overwrite) fileUri else target
        if (uri == null) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            var msg = ""
            var newBytes: ByteArray? = null
            var savedText: String? = null
            try {
                val text = dc.render(p.newBodies)
                val (out, note) = Encodings.encode(text, d)
                val stream = app.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("파일에 쓸 수 없어요")
                stream.use { it.write(out) }
                msg = "저장했어요 (${p.changedCount}줄 바뀜)" + (if (note != null) " · $note" else "")
                newBytes = out
                savedText = text
            } catch (e: SecurityException) {
                msg = "저장 권한이 없어요. '다른 이름으로 저장'을 써 주세요"
            } catch (e: Exception) {
                msg = "저장 실패: ${e.message ?: e}"
            }
            withContext(Dispatchers.Main) {
                message = msg
                busy = false
                if (savedText != null) resultText = savedText   // 저장 직후 바뀐 전체 텍스트 표시
                // 원본에 덮어썼다면 새 내용을 기준으로 다시 읽는다
                if (overwrite && newBytes != null) {
                    bytes = newBytes
                    forcedCharset = null
                    reparse()
                }
            }
        }
    }

    // ---------------- 규칙 ----------------

    private fun commitRules(list: List<Rule>) {
        rules = list
        viewModelScope.launch(Dispatchers.IO) { ruleStore.save(list) }
        recompute()
    }

    private fun nextId(): Long = (rules.maxOfOrNull { it.id } ?: 0L) + 1

    fun addRule(find: String, replace: String, regex: Boolean) {
        commitRules(rules + Rule(nextId(), find, replace, regex, true))
    }

    fun updateRule(r: Rule) {
        commitRules(rules.map { if (it.id == r.id) r else it })
    }

    fun deleteRule(id: Long) {
        commitRules(rules.filter { it.id != id })
    }

    /** 가져오기: 같은 (찾을 말, 정규식 여부) 규칙은 바꿀 말만 갱신하고, 없는 것은 추가한다 */
    fun importRules(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val raw = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("파일을 열 수 없어요")
                val text = Encodings.decode(raw, null).text
                withContext(Dispatchers.Main) {
                    val incoming = RuleStore.fromTsv(text, nextId())
                    var added = 0
                    var updated = 0
                    var list = rules
                    for (r in incoming) {
                        val same = list.firstOrNull { it.find == r.find && it.regex == r.regex }
                        if (same == null) {
                            list = list + r.copy(id = (list.maxOfOrNull { it.id } ?: 0L) + 1)
                            added++
                        } else {
                            list = list.map { if (it.id == same.id) it.copy(replace = r.replace, enabled = r.enabled) else it }
                            updated++
                        }
                    }
                    commitRules(list)
                    message = "규칙 가져오기: ${added}개 추가, ${updated}개 갱신"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "가져오기 실패: ${e.message ?: e}" }
            }
        }
    }

    fun exportRules(uri: Uri) {
        val snapshot = rules
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                app.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(RuleStore.toTsv(snapshot).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("파일에 쓸 수 없어요")
                "규칙 ${snapshot.size}개를 내보냈어요"
            } catch (e: Exception) {
                "내보내기 실패: ${e.message ?: e}"
            }
            withContext(Dispatchers.Main) { message = msg }
        }
    }

    // ---------------- 설정 ----------------

    fun updateSettings(o: CleanOptions) {
        settings = o
        settingsStore.save(o)
        recompute()
    }
}
