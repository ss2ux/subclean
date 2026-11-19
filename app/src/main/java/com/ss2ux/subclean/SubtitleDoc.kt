package com.ss2ux.subclean

enum class Kind(val label: String) { SRT("SRT"), VTT("VTT"), LRC("LRC"), TXT("TXT") }

/**
 * 파일의 한 줄.
 *  - prefix   : 손대지 않는 앞부분 (LRC 의 [00:12.34] 시간 태그)
 *  - body     : 다듬기 대상 본문
 *  - eol      : 원래 줄바꿈 문자 (\r\n, \n, \r, 마지막 줄이면 "")
 *  - editable : false 면 번호/타임스탬프/빈 줄/헤더 등이라 절대 건드리지 않는다
 */
class Line(val prefix: String, val body: String, val eol: String, val editable: Boolean)

/** 파일 전체를 "줄 단위 + 줄바꿈 그대로" 로 들고 있어서, 본문만 바꿔도 나머지는 한 글자도 달라지지 않는다 */
class Doc(val kind: Kind, val lines: List<Line>, val cueNos: List<Int?> = emptyList()) {
    val editableCount: Int get() = lines.count { it.editable }

    /** newBodies[i] 는 lines[i] 의 새 본문 (같은 길이). 줄바꿈과 prefix 는 원본 그대로 */
    fun render(newBodies: List<String>): String {
        val sb = StringBuilder()
        for (i in lines.indices) {
            val ln = lines[i]
            sb.append(ln.prefix).append(newBodies[i]).append(ln.eol)
        }
        return sb.toString()
    }

    companion object {
        private val EOL = Regex("\r\n|\n|\r")
        private val CUE_NUMBER = Regex("""^\d{1,9}$""")
        private val LRC_LINE = Regex("""^((?:\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\])+)(.*)$""")
        private val TIMING = Regex(
            """^\s*(?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3}\s*-->\s*(?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3}.*$"""
        )

        fun detectKind(fileName: String, text: String): Kind {
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "srt" -> return Kind.SRT
                "vtt" -> return Kind.VTT
                "lrc" -> return Kind.LRC
            }
            // .txt 등은 내용을 보고 판단
            val head = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            if (head.startsWith("WEBVTT")) return Kind.VTT
            val rows = text.split(EOL)
            if (rows.any { TIMING.matches(it) }) return Kind.SRT
            if (rows.any { LRC_LINE.matches(it) }) return Kind.LRC
            return Kind.TXT
        }

        fun parse(fileName: String, text: String): Doc {
            val clean = text.removePrefix("\uFEFF")
            val kind = detectKind(fileName, clean)

            // 줄과 줄바꿈 문자를 함께 쪼갠다
            val raw = ArrayList<Pair<String, String>>()
            var start = 0
            for (m in EOL.findAll(clean)) {
                raw.add(clean.substring(start, m.range.first) to m.value)
                start = m.range.last + 1
            }
            if (start < clean.length) raw.add(clean.substring(start) to "")

            val lines = ArrayList<Line>(raw.size)
            // lines 와 같은 길이. SRT/VTT 에서 자막 한 개(Cue)의 대사 줄이면 그 Cue 번호, 나머지는 null
            val cueNos = ArrayList<Int?>(raw.size)
            when (kind) {
                Kind.TXT -> for ((t, eol) in raw) lines.add(Line("", t, eol, t.isNotBlank()))
                Kind.LRC -> for ((t, eol) in raw) {
                    val m = LRC_LINE.matchEntire(t)
                    if (m != null && m.groupValues[2].isNotBlank()) {
                        lines.add(Line(m.groupValues[1], m.groupValues[2], eol, true))
                    } else {
                        lines.add(Line("", t, eol, false))   // [ti:..] [ar:..] 같은 정보 줄, 빈 줄
                    }
                }
                Kind.SRT, Kind.VTT -> {
                    var inCue = false
                    var inNote = false
                    var ordinal = 0          // 타이밍 줄이 나온 순서 (1부터)
                    var curCue: Int? = null
                    for ((idx, pair) in raw.withIndex()) {
                        val (t, eol) = pair
                        var editable = false
                        if (t.isBlank()) {
                            inCue = false
                            inNote = false
                        } else if (inNote) {
                            // VTT NOTE / STYLE / REGION 블록 안
                        } else if (inCue) {
                            editable = true
                        } else if (TIMING.matches(t)) {
                            inCue = true
                            ordinal++
                            // 타이밍 바로 윗줄이 숫자면 파일에 적힌 Cue 번호를 쓰고, 없으면 순서 번호를 쓴다
                            val above = if (idx > 0) raw[idx - 1].first.trim() else ""
                            curCue = if (CUE_NUMBER.matches(above)) above.toIntOrNull() ?: ordinal else ordinal
                        } else if (kind == Kind.VTT &&
                            (t.startsWith("NOTE") || t.startsWith("STYLE") || t.startsWith("REGION"))
                        ) {
                            inNote = true
                        }
                        lines.add(Line("", t, eol, editable))
                        cueNos.add(if (editable) curCue else null)
                    }
                }
            }
            return Doc(kind, lines, if (cueNos.size == lines.size) cueNos else emptyList())
        }
    }
}
