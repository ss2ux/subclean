package com.ss2ux.subclean

/**
 * 긴 텍스트를 글자 수 제한(DeepL 무료 5,000자 등) 안으로 문장 단위로 나누는 순수 로직.
 * 웹앱 'DeepL Smart Splitter' 의 smartSplit 과 같은 규칙이다.
 *  1) 제한의 50% 지점 뒤에 빈 줄(문단 끝)이 있으면 거기서 자른다
 *  2) 없으면 40% 지점 뒤의 마지막 문장 끝(. ! ? 。 ！ ？ 줄바꿈)에서 자른다
 *  3) 없으면 30% 지점 뒤의 마지막 공백에서 자른다
 *  4) 그래도 없으면 제한 글자 수에서 그냥 자른다 (이모지 같은 서로게이트 쌍은 쪼개지 않는다)
 */
object Splitter {
    const val MIN_LIMIT = 500
    const val MAX_LIMIT = 5000
    const val DEFAULT_LIMIT = 4500

    private fun isEnd(c: Char) = c == '.' || c == '!' || c == '?' || c == '。' || c == '！' || c == '？' || c == '\n'

    fun split(input: String, limitRaw: Int): List<String> {
        val limit = limitRaw.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val out = ArrayList<String>()
        var text = input
        if (text.isBlank()) return out
        while (text.isNotEmpty()) {
            if (text.length <= limit) {
                out.add(text.trim())
                break
            }
            val area = text.substring(0, limit)
            var idx = -1
            val para = area.lastIndexOf("\n\n")
            if (para > limit * 0.5) idx = para + 2
            if (idx == -1) {
                var last = -1
                for (i in area.indices) if (isEnd(area[i])) last = i
                if (last > limit * 0.4) idx = last + 1
            }
            if (idx == -1) {
                val sp = area.lastIndexOf(' ')
                if (sp > limit * 0.3) idx = sp + 1
            }
            if (idx == -1) {
                idx = limit
                if (Character.isHighSurrogate(text[idx - 1])) idx -= 1
            }
            val c = text.substring(0, idx).trim()
            if (c.isNotEmpty()) out.add(c)
            text = text.substring(idx).trim()
        }
        return out
    }
}
