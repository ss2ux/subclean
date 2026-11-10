package com.ss2ux.subclean

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
class PreviewRow(val index: Int, val before: String, val after: String, val excluded: Boolean = false) {
    /** 실제로 바뀌는 줄인가 (사용자가 제외한 줄은 바뀌지 않는 것으로 친다) */
    val changed: Boolean get() = !excluded && before != after
}

class Preview(
    val newBodies: List<String>,   // 모든 줄의 새 본문 (편집 대상이 아닌 줄은 원래 그대로)
    val rows: List<PreviewRow>,    // 편집 대상 줄만
    val changedCount: Int,
    val excludedCount: Int,        // 사용자가 눌러서 "바꾸지 않기"로 뺀 줄 수
    val problems: List<String>,
    val totalChars: Int = 0,       // 저장·복사되는 결과 전체 글자 수 (타임스탬프·줄바꿈 포함, 제외한 줄 반영)
    val dialogueChars: Int = 0     // 대사 본문만 이어 붙인 글자 수 (줄 사이 줄바꿈 포함, 제외한 줄 반영)
)

/** 앱에 들어 있는 말투 팩의 버전. speech_pack.tsv 를 고쳐서 새로 넣어야 할 때 숫자를 올린다 */
const val SPEECH_PACK_VERSION = 3   // 2: 높임 '너 -> 당신' 규칙 추가, 3: 독백(...)에는 높임 안 붙임 + 반말 '당신 -> 너'

/** 원본 백업을 못 해서 덮어쓰기를 멈출 때 */
private class BackupFailed(message: String) : Exception(message)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    private val ruleStore = RuleStore(app)
    private val settingsStore = SettingsStore(app)

    private val loadedRules = ruleStore.load()
    private val dedupedRules = RuleStore.dedupe(loadedRules)
    var rules by mutableStateOf(dedupedRules)
        private set
    var settings by mutableStateOf(settingsStore.load())
        private set

    // 규칙 탭 정렬: 0 최신순, 1 오래된순, 2 찾을 말 가나다순 (보기 전용)
    var ruleSort by mutableStateOf(settingsStore.ruleSort)
        private set

    // 덮어쓰기 직전 원본을 같은 폴더의 '원본 자막' 폴더에 한 번 백업
    var backupOriginal by mutableStateOf(settingsStore.backupOriginal)
        private set

    fun updateBackupOriginal(v: Boolean) {
        backupOriginal = v
        settingsStore.backupOriginal = v
    }

    // 열린 파일
    var fileName by mutableStateOf<String?>(null)
        private set
    var fileUri by mutableStateOf<Uri?>(null)
        private set
    /** 마지막으로 연 파일 (앱 재시작 후에도 유지). 파일 선택기 시작 위치·안내용 */
    var lastOpenUri by mutableStateOf(settingsStore.lastOpenUri?.let { runCatching { Uri.parse(it) }.getOrNull() })
        private set
    var lastOpenName by mutableStateOf(settingsStore.lastOpenName)
        private set
    var forcedCharset by mutableStateOf<Charset?>(null)   // null = 자동 감지
        private set
    var decoded by mutableStateOf<Decoded?>(null)
        private set
    var doc by mutableStateOf<Doc?>(null)
        private set
    var preview by mutableStateOf<Preview?>(null)
        private set

    // '결과 전체 보기' 창에 넣을 텍스트. null 이면 창이 닫혀 있음
    var resultText by mutableStateOf<String?>(null)
        private set

    // UI 가 클립보드에 넣을 텍스트. 넣으면 Compose 쪽에서 복사 후 clearClipboard() 호출
    var pendingClipboard by mutableStateOf<String?>(null)
        private set

    // 저장·재파싱 후에도 아직 바뀌는 줄이 있을 때 true → UI 가 '다시 적용' 확인창을 띄움
    var offerAnotherPass by mutableStateOf(false)
        private set

    // 사용자가 "이 줄은 바꾸지 않기"로 뺀 줄 번호들 (파일을 새로 열거나 다시 읽으면 비워진다)
    var excluded by mutableStateOf<Set<Int>>(emptySet())
        private set

    // 분할 탭 (5천자 넘는 텍스트를 DeepL 에 나눠 넣기). 탭을 옮겨도 남도록 여기에 둔다
    var splitText by mutableStateOf("")
        private set
    var splitLimit by mutableIntStateOf(settingsStore.splitLimit)
        private set
    var splitChunks by mutableStateOf<List<String>>(emptyList())
        private set
    var splitCopied by mutableStateOf<Set<Int>>(emptySet())
        private set
    // true 면 마지막 조각부터 조각 1까지 거꾸로 복사한다 (클립보드 기록은 나중에 복사한 게 위에 보여서)
    var splitReverse by mutableStateOf(settingsStore.splitReverse)
        private set
    private var splitJob: Job? = null
    // 분할 입력칸의 글이 '다듬기 탭에서 복사할 때 자동으로 들어온 것'이면 true.
    // 사용자가 직접 고치거나 지우거나 파일·자막을 가져오면 false 가 되어, 파일이 바뀌어도 지우지 않는다
    private var splitAutoInserted = false

    // ---- 규칙 충돌 점검 ----
    // null = 아직 점검 안 함. 규칙·제외 단어를 바꾸면 checkStale 이 true 가 되어 '다시 점검' 안내를 띄운다
    var ruleFindings by mutableStateOf<List<Finding>?>(null)
        private set
    var checkStale by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)
        private set
    private var checkJob: Job? = null

    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    // ---- 1차 다듬기에서 생성된 한글 보호 (줄 단위) ----
    /** 1차 다듬기 후 원본 덮어쓰기 때: 한글이 있던 줄 번호 + 그 줄의 한글 단어들 */
    private var firstPassLineKoreans: List<FirstPassLineKorean>? = null
    /** 새로고침 후 사라진 1차 한글 목록. null 이면 다이얼로그 없음 */
    var pendingMissingKoreans by mutableStateOf<List<MissingKorean>?>(null)
        private set

    private var bytes: ByteArray? = null
    private var job: Job? = null

    init {
        // 이미 저장돼 있던 완전히 같은 규칙은 하나만 남기고 파일에도 반영한다
        if (dedupedRules.size != loadedRules.size) {
            viewModelScope.launch(Dispatchers.IO) { ruleStore.save(dedupedRules) }
        }
        // 처음 실행하거나, 앱 업데이트로 말투 팩이 새로워졌을 때 기본 말투 규칙을 자동으로 넣는다
        if (settingsStore.speechPackVersion < SPEECH_PACK_VERSION) autoInstallSpeechPack()
    }

    fun clearMessage() { message = null }

    fun closeResult() { resultText = null }

    fun clearClipboard() { pendingClipboard = null }

    fun dismissAnotherPass() { offerAnotherPass = false }

    /** 지금 미리보기 기준으로 바뀐 전체 텍스트를 만들어 보여 준다 (저장 안 해도 됨) */
    fun showResult() {
        val dc = doc ?: return
        val p = preview ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val text = dc.render(p.newBodies)
            withContext(Dispatchers.Main) { resultText = text }
        }
    }

    /** 지금 미리보기 결과(제외한 줄 반영) 전체를 클립보드에 넣도록 UI 에 넘긴다. 저장 없이도 쓸 수 있다 */
    fun copyResult() {
        val dc = doc ?: return
        val p = preview ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val text = dc.render(p.newBodies)
            withContext(Dispatchers.Main) {
                pendingClipboard = text
                insertCopiedToSplit(text)
                message = "결과를 복사했어요 (${text.length}자) · 분할 탭에도 넣었어요"
            }
        }
    }

    /** 지금 미리보기 결과 전체 텍스트를 만들어 [onReady] 로 준다 (메인 스레드에서 불림). DeepL 로 보내기 전 복사용.
     *  복사하는 글이므로 분할 입력칸에도 같이 넣는다. */
    fun withResultText(onReady: (String) -> Unit) {
        val dc = doc ?: return
        val p = preview ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val text = dc.render(p.newBodies)
            withContext(Dispatchers.Main) {
                insertCopiedToSplit(text)
                onReady(text)
            }
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
                        // 다른 파일로 바뀌면, 복사 때 자동으로 들어왔던 분할 입력칸의 글은 지운다
                        if (fileUri != uri && splitAutoInserted) {
                            splitText = ""
                            splitAutoInserted = false
                            resplit()
                        }
                        bytes = data
                        fileUri = uri
                        fileName = name
                        forcedCharset = null
                        // 새 파일이면 1차 한글 기억·차이 목록을 초기화
                        firstPassLineKoreans = null
                        pendingMissingKoreans = null
                        // 다음에 파일 선택기를 열 때 이 위치부터 보이도록 기억
                        lastOpenUri = uri
                        lastOpenName = name
                        settingsStore.lastOpenUri = uri.toString()
                        settingsStore.lastOpenName = name
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
        excluded = emptySet()   // 줄 번호가 달라질 수 있으니 제외 목록은 비운다
        recompute()
    }

    /** 다듬기 탭에서 줄을 누르면: 바뀌는 줄은 제외(원래대로 둠), 이미 제외한 줄은 다시 포함 */
    fun toggleExclude(index: Int) {
        excluded = if (index in excluded) excluded - index else excluded + index
        recompute()
    }

    fun clearExcluded() {
        if (excluded.isEmpty()) return
        excluded = emptySet()
        recompute()
    }

    /** 열어 둔 파일을 다시 읽는다 (다른 앱에서 고치고 돌아왔을 때). 수동으로 고른 인코딩은 유지한다.
     *  DeepL로 보낸 뒤 돌아온 경우에는 한글이 바뀌었는지 검사해서 복원 확인 창을 띄운다. */
    fun reloadFile(onDone: () -> Unit = {}) {
        val uri = fileUri
        if (uri == null) {
            onDone()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var msg: String? = null
            var data: ByteArray? = null
            try {
                data = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (data == null) msg = "파일을 열 수 없어요"
                else if (data.size > 20_000_000) { msg = "파일이 너무 커요 (20MB 초과)"; data = null }
            } catch (e: Exception) {
                msg = "다시 불러오기 실패: ${e.message ?: e}"
                data = null
            }
            withContext(Dispatchers.Main) {
                if (data != null) {
                    bytes = data
                    reparse()
                    msg = "다시 불러왔어요"
                    // DeepL 보내기 때 기억해 둔 한글이 있으면 차이 검사
                    checkKoreanAfterReload()
                }
                message = msg
                onDone()
            }
        }
    }

    /** 새로고침 직후: 1차 때 한글이 있던 줄에서 그 단어가 사라졌는지 검사 */
    private fun checkKoreanAfterReload() {
        val firstPass = firstPassLineKoreans ?: return
        if (firstPass.isEmpty()) return
        val dc = doc ?: return
        val currentBodies = dc.lines.mapIndexed { i, ln -> i to ln.body }.toMap()
        val missing = findMissingKoreans(firstPass, currentBodies)
        pendingMissingKoreans = if (missing.isNotEmpty()) missing else null
    }

    /**
     * 사용자가 고른 항목을 반영한다.
     * replacements: MissingKorean.id → 그 줄에 넣을 최종 본문 문자열
     * (같은 줄에 여러 단어가 있으면 UI에서 줄 단위로 합쳐서 넘겨준다)
     */
    fun applyMissingKoreanFixes(lineReplacements: Map<Int, String>) {
        val dc = doc
        if (dc == null || lineReplacements.isEmpty()) {
            pendingMissingKoreans = null
            return
        }
        val newBodies = dc.lines.mapIndexed { i, ln ->
            if (ln.editable && i in lineReplacements) lineReplacements[i]!! else ln.body
        }
        viewModelScope.launch(Dispatchers.Default) {
            val s = settings
            val r = rules.filter { it.speech == Speech.ALL || it.speech == s.speech }
            val cleaner = Cleaner(r, s)
            val commonCleaner = if (s.speech == Speech.ALL) cleaner else Cleaner(r.filter { it.speech == Speech.ALL }, s)
            val monologue = Regex("\\.\\.|…")
            val bodies = ArrayList<String>(dc.lines.size)
            val rows = ArrayList<PreviewRow>()
            var changed = 0
            var excludedCount = 0
            var total = 0
            var dialogue = 0
            var dialogueLines = 0
            val ex = excluded
            for ((i, ln) in dc.lines.withIndex()) {
                val body = newBodies[i]
                if (!ln.editable) {
                    bodies.add(body)
                    total += ln.prefix.length + body.length + ln.eol.length
                } else {
                    val after = (if (monologue.containsMatchIn(body)) commonCleaner else cleaner).clean(body)
                    val skip = i in ex && after != body
                    val finalBody = if (skip) body else after
                    bodies.add(finalBody)
                    total += ln.prefix.length + finalBody.length + ln.eol.length
                    dialogue += finalBody.length
                    dialogueLines++
                    rows.add(PreviewRow(i, body, after, skip))
                    if (skip) excludedCount++ else if (after != body) changed++
                }
            }
            val p = Preview(
                bodies, rows, changed, excludedCount,
                (cleaner.problems + commonCleaner.problems).distinct(),
                totalChars = total,
                dialogueChars = dialogue + (dialogueLines - 1).coerceAtLeast(0)
            )
            withContext(Dispatchers.Main) {
                val restoredLines = dc.lines.mapIndexed { i, ln ->
                    Line(ln.prefix, newBodies[i], ln.eol, ln.editable)
                }
                doc = Doc(dc.kind, restoredLines)
                preview = p
                pendingMissingKoreans = null
                firstPassLineKoreans = null
                message = "선택한 ${lineReplacements.size}개 줄을 수정했어요"
            }
        }
    }

    fun dismissMissingKoreans() {
        pendingMissingKoreans = null
        firstPassLineKoreans = null
    }

    // ---------------- 다듬기 미리보기 ----------------

    fun recompute() {
        val d = doc
        if (d == null) {
            preview = null
            return
        }
        val s = settings
        val ex = excluded
        // 공통 규칙 + 지금 고른 말투의 규칙만 쓴다 (저장 순서는 그대로 유지: 정규식은 순서대로 적용되므로)
        val r = rules.filter { it.speech == Speech.ALL || it.speech == s.speech }
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.Default) {
            val cleaner = Cleaner(r, s)
            // '...' 이나 '…' 가 들어 있는 줄은 독백으로 보고 높임/반말 규칙을 빼고 공통 규칙만 적용한다
            val commonCleaner = if (s.speech == Speech.ALL) cleaner else Cleaner(r.filter { it.speech == Speech.ALL }, s)
            val monologue = Regex("\\.\\.|…")
            val bodies = ArrayList<String>(d.lines.size)
            val rows = ArrayList<PreviewRow>()
            var changed = 0
            var excludedCount = 0
            var total = 0
            var dialogue = 0
            var dialogueLines = 0
            for ((i, ln) in d.lines.withIndex()) {
                if (!ln.editable) {
                    bodies.add(ln.body)
                    total += ln.prefix.length + ln.body.length + ln.eol.length
                } else {
                    val after = (if (monologue.containsMatchIn(ln.body)) commonCleaner else cleaner).clean(ln.body)
                    val skip = i in ex && after != ln.body
                    val finalBody = if (skip) ln.body else after
                    bodies.add(finalBody)   // 제외한 줄은 원래 그대로 둔다
                    total += ln.prefix.length + finalBody.length + ln.eol.length
                    dialogue += finalBody.length
                    dialogueLines++
                    rows.add(PreviewRow(i, ln.body, after, skip))
                    if (skip) excludedCount++ else if (after != ln.body) changed++
                }
            }
            val p = Preview(
                bodies, rows, changed, excludedCount,
                (cleaner.problems + commonCleaner.problems).distinct(),
                totalChars = total,
                dialogueChars = dialogue + (dialogueLines - 1).coerceAtLeast(0)
            )
            withContext(Dispatchers.Main) { preview = p }
        }
    }

    // ---------------- 저장 ----------------

    /** overwrite = true 면 열었던 원본 파일에 덮어쓰고, false 면 target 에 새로 저장.
     *  성공 시 결과를 클립보드와 분할 입력칸에 넣는다. 치환 연쇄(A→B→C)는 Cleaner 가 한 번에 처리한다. */
    fun save(target: Uri?, overwrite: Boolean) {
        val d = decoded ?: return
        val dc = doc ?: return
        val p = preview ?: return
        val uri = if (overwrite) fileUri else target
        if (uri == null) return
        busy = true
        offerAnotherPass = false
        val doBackup = overwrite && backupOriginal
        viewModelScope.launch(Dispatchers.IO) {
            var msg = ""
            var newBytes: ByteArray? = null
            var savedText: String? = null
            var backupNote: String? = null
            val changed = p.changedCount
            try {
                // 덮어쓰기 전에 원본을 '원본 자막' 폴더에 한 번만 저장. 못 하면 원본을 지키려고 덮어쓰지 않는다
                if (doBackup) {
                    val r = OriginalBackup.backupOnce(app, uri) {
                        app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    if (!r.ok) throw BackupFailed(r.error ?: "원본 백업에 실패해서 덮어쓰지 않았어요")
                    backupNote = r.note
                }
                val text = dc.render(p.newBodies)
                val (out, note) = Encodings.encode(text, d)
                val stream = app.contentResolver.openOutputStream(uri, "wt")
                    ?: throw IllegalStateException("파일에 쓸 수 없어요")
                stream.use { it.write(out) }
                msg = "저장·복사했어요 (${changed}줄 바뀜)" + (if (note != null) " · $note" else "") +
                    (if (backupNote != null) " · $backupNote" else "")
                newBytes = out
                savedText = text
            } catch (e: BackupFailed) {
                msg = e.message ?: "원본 백업에 실패해서 덮어쓰지 않았어요"
            } catch (e: SecurityException) {
                msg = "저장 권한이 없어요. '다른 이름으로 저장'을 써 주세요"
            } catch (e: Exception) {
                msg = "저장 실패: ${e.message ?: e}"
            }
            withContext(Dispatchers.Main) {
                if (savedText != null) {
                    pendingClipboard = savedText
                    insertCopiedToSplit(savedText)   // 저장하면서 복사된 글도 분할 입력칸에 넣는다
                    // 1차 다듬기 후 원본 덮어쓰기일 때만: 한글이 있는 줄을 기억한다
                    if (overwrite && firstPassLineKoreans == null) {
                        val list = ArrayList<FirstPassLineKorean>()
                        for ((i, ln) in dc.lines.withIndex()) {
                            if (!ln.editable) continue
                            val body = p.newBodies[i]
                            val words = extractHangulRuns(body)
                            if (words.isNotEmpty()) {
                                list.add(FirstPassLineKorean(i, words))
                            }
                        }
                        firstPassLineKoreans = list
                    }
                }
                message = if (savedText != null) "$msg · 분할 탭에도 넣었어요" else msg
                if (overwrite && newBytes != null) {
                    bytes = newBytes
                    forcedCharset = null
                    reparse()
                }
                busy = false
            }
        }
    }

    // ---------------- 분할 ----------------

    /** 사용자가 직접 넣거나 고친 글 (붙여 넣기, 파일 열기, 자막 가져오기, 지우기) */
    fun updateSplitText(t: String) {
        splitAutoInserted = false
        splitText = t
        resplit()
    }

    /** 다듬기 탭에서 결과를 복사하면 같은 글을 분할 입력칸에도 넣는다. 파일이 바뀌면 자동으로 지워진다 */
    fun insertCopiedToSplit(text: String) {
        splitText = text
        splitAutoInserted = true
        resplit()
    }

    fun updateSplitReverse(v: Boolean) {
        splitReverse = v
        settingsStore.splitReverse = v
    }

    fun updateSplitLimit(v: Int) {
        val c = v.coerceIn(1000, Splitter.MAX_LIMIT)
        splitLimit = c
        settingsStore.splitLimit = c
        resplit()
    }

    /** 글이나 제한이 바뀔 때마다 다시 나눈다. 복사 표시는 새로 시작 */
    private fun resplit() {
        val t = splitText
        val lim = splitLimit
        splitCopied = emptySet()
        splitJob?.cancel()
        splitJob = viewModelScope.launch(Dispatchers.Default) {
            val r = Splitter.split(t, lim)
            withContext(Dispatchers.Main) { splitChunks = r }
        }
    }

    fun markSplitCopied(i: Int) {
        splitCopied = splitCopied + i
    }

    fun resetSplitCopied() {
        splitCopied = emptySet()
    }

    /** 다듬기 탭에 열어 둔 자막의 대사만(타임스탬프·번호 제외, 다듬기 전 원문) 분할 입력칸에 넣는다 */
    fun importOpenSubtitleToSplit() {
        val d = doc
        if (d == null) {
            message = "다듬기 탭에서 자막 파일을 먼저 열어 주세요"
            return
        }
        updateSplitText(d.lines.filter { it.editable }.joinToString("\n") { it.body })
        message = "대사 ${d.editableCount}줄을 가져왔어요"
    }

    /** 분할 탭에서 파일을 직접 연다. 자막(.srt .vtt .lrc)이면 대사만, 일반 텍스트면 전부 넣는다 */
    fun loadSplitFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            var msg: String? = null
            var body: String? = null
            try {
                var name = ""
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: ""
                }
                val raw = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (raw == null) {
                    msg = "파일을 열 수 없어요"
                } else if (raw.size > 20_000_000) {
                    msg = "파일이 너무 커요 (20MB 초과)"
                } else {
                    val text = Encodings.decode(raw, null).text
                    val d = Doc.parse(name, text)
                    body = if (d.kind == Kind.TXT) text.removePrefix("\uFEFF")
                    else d.lines.filter { it.editable }.joinToString("\n") { it.body }
                    msg = if (d.kind == Kind.TXT) "파일을 불러왔어요" else "자막 대사 ${d.editableCount}줄을 불러왔어요"
                }
            } catch (e: Exception) {
                msg = "열기 실패: ${e.message ?: e}"
            }
            withContext(Dispatchers.Main) {
                if (body != null) updateSplitText(body)
                message = msg
            }
        }
    }

    // ---------------- 규칙 ----------------

    private fun commitRules(list: List<Rule>) {
        rules = list
        checkStale = true
        viewModelScope.launch(Dispatchers.IO) { ruleStore.save(list) }
        recompute()
    }

    private fun nextId(): Long = (rules.maxOfOrNull { it.id } ?: 0L) + 1

    fun addRule(find: String, replace: String, regex: Boolean, speech: Speech = Speech.ALL, polish: Boolean = false) {
        // 찾을 말·바꿀 말·정규식 여부·말투가 전부 같은 규칙은 다시 등록하지 않는다
        if (rules.any { RuleStore.sameRule(it, find, replace, regex, speech) }) {
            message = "똑같은 규칙이 이미 있어요"
            return
        }
        commitRules(rules + Rule(nextId(), find, replace, regex, true, speech, polish))
    }

    /**
     * 말투 팩(res/raw/speech_pack.tsv)을 읽어 규칙 목록 끝에 없는 것만 추가한다. 이미 있는 규칙은 건드리지 않는다.
     * 반환: 새로 추가한 개수. 팩을 읽지 못하면 예외.
     */
    private fun mergeSpeechPack(): Int {
        val text = app.resources.openRawResource(R.raw.speech_pack).use { String(it.readBytes(), Charsets.UTF_8) }
        val incoming = RuleStore.fromTsv(text, nextId())
        var list = rules
        var added = 0
        // 팩 버전 3 이전에 설치된 옛 높임 규칙은 '...' 독백에도 요를 붙였다. 옛 문장 끝 패턴이 든 높임 규칙과
        // 새 팩의 높임 규칙은 한 번 지우고, 새 팩이 올바른 순서로 다시 넣게 한다.
        fun isHonorific(s: Speech) = s == Speech.HONORIFIC1 || s == Speech.HONORIFIC2
        if (settingsStore.speechPackVersion < 3) {
            val hFinds = incoming.filter { isHonorific(it.speech) && it.regex }.map { it.find }.toSet()
            list = list.filterNot {
                isHonorific(it.speech) && it.regex && (it.find in hFinds || it.find.contains("(?=[.!?…~]+"))
            }
        }
        val removed = rules.size - list.size
        for (r in incoming) {
            val exists = list.any { it.find == r.find && it.regex == r.regex && it.speech == r.speech }
            if (!exists) {
                list = list + r.copy(id = (list.maxOfOrNull { it.id } ?: 0L) + 1)
                added++
            }
        }
        if (added > 0 || removed > 0) commitRules(list)
        return added
    }

    /** 앱을 처음 켤 때(또는 팩이 갱신된 뒤 처음 켤 때) 조용히 팩을 넣는다. 실패하면 다음 실행 때 다시 시도한다 */
    private fun autoInstallSpeechPack() {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                mergeSpeechPack()
                settingsStore.speechPackVersion = SPEECH_PACK_VERSION
            } catch (e: Exception) {
                message = "기본 말투 규칙을 불러오지 못했어요: ${e.message ?: e}"
            }
        }
    }

    fun changeRuleSort(v: Int) {
        ruleSort = v
        settingsStore.ruleSort = v
    }

    fun updateRule(r: Rule) {
        if (rules.any { it.id != r.id && RuleStore.sameRule(it, r.find, r.replace, r.regex, r.speech) }) {
            message = "똑같은 규칙이 이미 있어요"
            return
        }
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
                        val same = list.firstOrNull { it.find == r.find && it.regex == r.regex && it.speech == r.speech }
                        if (same == null) {
                            list = list + r.copy(id = (list.maxOfOrNull { it.id } ?: 0L) + 1)
                            added++
                        } else {
                            list = list.map { if (it.id == same.id) it.copy(replace = r.replace, enabled = r.enabled) else it }
                            updated++
                        }
                    }
                    commitRules(RuleStore.dedupe(list))
                    message = "규칙 가져오기: ${added}개 추가, ${updated}개 갱신"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = "가져오기 실패: ${e.message ?: e}" }
            }
        }
    }

    fun exportRules(uri: Uri, regexOnly: Boolean = false) {
        val snapshot = if (regexOnly) rules.filter { it.regex } else rules
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                app.contentResolver.openOutputStream(uri, "wt")?.use {
                    it.write(RuleStore.toTsv(snapshot).toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("파일에 쓸 수 없어요")
                if (regexOnly) "정규식 규칙 ${snapshot.size}개를 내보냈어요" else "규칙 ${snapshot.size}개를 내보냈어요"
            } catch (e: Exception) {
                "내보내기 실패: ${e.message ?: e}"
            }
            withContext(Dispatchers.Main) { message = msg }
        }
    }

    // ---------------- 설정 ----------------

    fun updateSettings(o: CleanOptions) {
        if (o.protect != settings.protect) checkStale = true
        settings = o
        settingsStore.save(o)
        recompute()
    }

    // ---------------- 규칙 충돌 점검 ----------------

    /** 켜진 규칙 전부(말투별)와 제외 단어를 서로 맞춰 보고 결과를 ruleFindings 에 넣는다 */
    fun runRuleCheck() {
        checkJob?.cancel()
        checking = true
        val rs = rules
        val s = settings
        checkJob = viewModelScope.launch(Dispatchers.Default) {
            val res = try {
                RuleCheck.check(rs, s)
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                ruleFindings = res
                checkStale = false
                checking = false
            }
        }
    }

    /**
     * 규칙을 저장하기 전에, 이 규칙과 관련된 오류·경고만 빠르게 찾아 [onResult] 로 넘긴다.
     * editId 가 null 이면 새 규칙. 참고 단계는 저장 때마다 띄우면 번거로워서 뺀다.
     */
    fun precheckRule(
        editId: Long?, find: String, replace: String, regex: Boolean, speech: Speech,
        onResult: (List<Finding>) -> Unit
    ) {
        val id = editId ?: nextId()
        val enabled = editId?.let { eid -> rules.firstOrNull { it.id == eid }?.enabled } ?: true
        val cand = Rule(id, find, replace, regex, enabled, speech)
        val list = if (editId == null) rules + cand else rules.map { if (it.id == editId) cand else it }
        val s = settings
        viewModelScope.launch(Dispatchers.Default) {
            val res = try {
                RuleCheck.check(list, s, focusRule = id, includeInfo = false).filter { it.severity != Severity.INFO }
            } catch (e: Exception) {
                emptyList()
            }
            withContext(Dispatchers.Main) { onResult(res) }
        }
    }

    /** 제외 단어를 넣기 전에: 이 단어가 가려서 안 먹게 되는 규칙을 찾는다 (가벼운 정적 검사라 바로 돌려준다) */
    fun checkProtectWords(words: List<String>): List<Finding> =
        try {
            RuleCheck.checkProtectWords(rules, settings, words).filter { it.severity != Severity.INFO }
        } catch (e: Exception) {
            emptyList()
        }
}
