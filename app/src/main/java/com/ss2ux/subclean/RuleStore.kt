package com.ss2ux.subclean

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 치환 규칙 저장소: filesDir/rules.json 한 파일. 수십~수백 개 규모에 충분하다 */
class RuleStore(context: Context) {
    private val file = File(context.filesDir, "rules.json")

    fun load(): List<Rule> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Rule(
                    id = o.optLong("id", i.toLong() + 1),
                    find = o.optString("find"),
                    replace = o.optString("replace"),
                    regex = o.optBoolean("regex", false),
                    enabled = o.optBoolean("enabled", true),
                    speech = Speech.fromName(o.optString("speech", "ALL"))   // 이전 버전 파일에는 없음 -> 공통
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(rules: List<Rule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("find", r.find)
                    .put("replace", r.replace)
                    .put("regex", r.regex)
                    .put("enabled", r.enabled)
                    .put("speech", r.speech.name)
            )
        }
        // 쓰다가 꺼져도 기존 파일이 깨지지 않게 임시 파일에 쓰고 바꿔치기
        val tmp = File(file.parentFile, "rules.json.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private fun esc(s: String): String = buildString {
            for (ch in s) when (ch) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }

        private fun unesc(s: String): String = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    when (s[i + 1]) {
                        '\\' -> append('\\')
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        else -> { append(c); append(s[i + 1]) }
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

        /**
         * 내보내기 형식 (메모장으로도 편집 가능):
         *   찾을 말 <TAB> 바꿀 말 <TAB> 옵션
         * 옵션: R = 정규식, H = 높임 전용, C = 반말 전용, - = 꺼짐 (겹치면 RH-, RC 처럼 붙여 쓴다).
         *       H/C 가 없으면 공통. 이전 버전 파일도 그대로 열린다. 줄바꿈/탭은 \n \t 로, 역슬래시는 \\ 로 적는다.
         * # 로 시작하는 줄은 설명(무시)
         */
        fun toTsv(rules: List<Rule>): String {
            val sb = StringBuilder()
            sb.append("# 자막 다듬기 치환 규칙: 찾을 말<TAB>바꿀 말<TAB>옵션(R=정규식, H=높임, C=반말, -=꺼짐)\n")
            for (r in rules) {
                val flags = (if (r.regex) "R" else "") +
                    (when (r.speech) { Speech.HONORIFIC -> "H"; Speech.CASUAL -> "C"; Speech.ALL -> "" }) +
                    (if (r.enabled) "" else "-")
                sb.append(esc(r.find)).append('\t').append(esc(r.replace))
                if (flags.isNotEmpty()) sb.append('\t').append(flags)
                sb.append('\n')
            }
            return sb.toString()
        }

        fun fromTsv(text: String, firstId: Long): List<Rule> {
            val out = ArrayList<Rule>()
            var id = firstId
            for (line in text.removePrefix("\uFEFF").split('\n')) {
                val l = line.trimEnd('\r')
                if (l.isBlank() || l.startsWith("#")) continue
                val cols = l.split('\t')
                val find = unesc(cols[0])
                if (find.isEmpty()) continue
                val repl = if (cols.size > 1) unesc(cols[1]) else ""
                val flags = if (cols.size > 2) cols[2].trim().uppercase() else ""
                val speech = when {
                    'H' in flags -> Speech.HONORIFIC
                    'C' in flags -> Speech.CASUAL
                    else -> Speech.ALL
                }
                out.add(Rule(id++, find, repl, regex = 'R' in flags, enabled = '-' !in flags, speech = speech))
            }
            return out
        }
    }
}

/** 설정(반복 압축 등) 저장소 */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): CleanOptions {
        val d = CleanOptions()
        return CleanOptions(
            threshold = p.getInt("threshold", d.threshold),
            keep = p.getInt("keep", d.keep),
            normalize = p.getBoolean("normalize", d.normalize),
            compressChars = p.getBoolean("compressChars", d.compressChars),
            compressWords = p.getBoolean("compressWords", d.compressWords),
            compressUnits = p.getBoolean("compressUnits", d.compressUnits),
            fixJosa = p.getBoolean("fixJosa", d.fixJosa),
            protect = (p.getString("protect", "") ?: "").split('\n').filter { it.isNotBlank() },
            speech = Speech.fromName(p.getString("speech", d.speech.name))
        )
    }

    /** 마지막으로 자동 설치한 기본 말투 규칙 팩의 버전 (0 = 아직 설치 안 함) */
    var speechPackVersion: Int
        get() = p.getInt("speechPackVersion", 0)
        set(v) { p.edit().putInt("speechPackVersion", v).apply() }

    /** 규칙 탭 정렬 (0 = 최신순, 1 = 오래된순, 2 = 찾을 말 가나다순). 보기 전용이라 저장 순서는 바뀌지 않는다 */
    var ruleSort: Int
        get() = p.getInt("ruleSort", 0).coerceIn(0, 2)
        set(v) { p.edit().putInt("ruleSort", v).apply() }

    fun save(o: CleanOptions) {
        p.edit()
            .putInt("threshold", o.threshold)
            .putInt("keep", o.keep)
            .putBoolean("normalize", o.normalize)
            .putBoolean("compressChars", o.compressChars)
            .putBoolean("compressWords", o.compressWords)
            .putBoolean("compressUnits", o.compressUnits)
            .putBoolean("fixJosa", o.fixJosa)
            .putString("protect", o.protect.joinToString("\n"))
            .putString("speech", o.speech.name)
            .apply()
    }
}
