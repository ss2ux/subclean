package com.ss2ux.subclean

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 디코딩 결과. 저장할 때 같은 인코딩/BOM 으로 되돌리기 위해 charset 과 bom 을 함께 가진다 */
class Decoded(val text: String, val charset: Charset, val bom: ByteArray, val auto: Boolean)

class EncodingOption(val label: String, val charset: Charset)

/**
 * 한국어 / 중국어(간체·번체) / 일본어 / 영어 자막 파일의 인코딩을 알아내 읽고 쓴다.
 *
 * 자동 감지 순서
 *  1) BOM (UTF-8 / UTF-16)
 *  2) ASCII 뿐이면 UTF-8
 *  3) UTF-8 엄격 검사를 통과하면 UTF-8
 *  4) 레거시 후보(CP949, GB18030, Big5, Shift_JIS, EUC-JP)를 각각 엄격하게 디코딩해 보고,
 *     "그 언어에서 흔히 쓰는 글자가 얼마나 나오는지"로 점수를 매겨 가장 높은 것을 고른다.
 *     (틀린 인코딩으로 읽으면 흔한 글자 비율이 아주 낮고, 반각 가타카나·사용자 정의 문자 같은 쓰레기 글자가 섞인다)
 * 그래도 틀리면 화면에서 인코딩을 직접 고를 수 있다.
 */
object Encodings {
    private fun cs(vararg names: String): Charset? {
        for (n in names) {
            try {
                return Charset.forName(n)
            } catch (e: Exception) {
            }
        }
        return null
    }

    private val UTF8: Charset = Charsets.UTF_8
    private val CP949 = cs("x-windows-949", "EUC-KR")
    private val GB = cs("GB18030", "GBK")
    private val BIG5 = cs("Big5", "Big5-HKSCS")
    private val SJIS = cs("windows-31j", "Shift_JIS")
    private val EUCJP = cs("EUC-JP")

    /** 수동 선택용 목록 */
    val options: List<EncodingOption> = buildList {
        add(EncodingOption("UTF-8", UTF8))
        add(EncodingOption("UTF-16 LE", Charsets.UTF_16LE))
        add(EncodingOption("UTF-16 BE", Charsets.UTF_16BE))
        CP949?.let { add(EncodingOption("한국어 (CP949)", it)) }
        GB?.let { add(EncodingOption("중국어 간체 (GB18030)", it)) }
        BIG5?.let { add(EncodingOption("중국어 번체 (Big5)", it)) }
        SJIS?.let { add(EncodingOption("일본어 (Shift_JIS)", it)) }
        EUCJP?.let { add(EncodingOption("일본어 (EUC-JP)", it)) }
    }

    fun label(c: Charset): String = options.firstOrNull { it.charset == c }?.label ?: c.name()

    /** forced == null 이면 자동 감지 */
    fun decode(bytes: ByteArray, forced: Charset?): Decoded {
        val none = ByteArray(0)
        val auto = forced == null

        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() &&
            (auto || forced == UTF8)
        ) {
            return Decoded(String(bytes, 3, bytes.size - 3, UTF8), UTF8, bytes.copyOfRange(0, 3), auto)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            (auto || forced == Charsets.UTF_16LE)
        ) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), Charsets.UTF_16LE, bytes.copyOfRange(0, 2), auto)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() &&
            (auto || forced == Charsets.UTF_16BE)
        ) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), Charsets.UTF_16BE, bytes.copyOfRange(0, 2), auto)
        }
        if (forced != null) return Decoded(String(bytes, forced), forced, none, false)

        if (bytes.all { it >= 0 }) return Decoded(String(bytes, UTF8), UTF8, none, true)
        strictDecode(bytes, UTF8)?.let { return Decoded(it, UTF8, none, true) }

        var best: Charset? = null
        var bestText: String? = null
        var bestScore = -1e9
        val candidates = listOf(CP949 to "ko", GB to "zh", BIG5 to "zh", SJIS to "ja", EUCJP to "ja")
        for ((c, lang) in candidates) {
            if (c == null) continue
            val t = strictDecode(bytes, c) ?: continue
            val s = score(t, lang)
            if (s > bestScore) {
                bestScore = s
                best = c
                bestText = t
            }
        }
        if (best != null && bestText != null) return Decoded(bestText, best, none, true)
        val fb = CP949 ?: UTF8
        return Decoded(String(bytes, fb), fb, none, true)
    }

    /** 원래 인코딩/BOM 으로 되돌려 인코딩. 원래 인코딩으로 표현 못 하는 글자가 있으면 UTF-8 로 저장하고 안내 문구를 돌려준다 */
    fun encode(text: String, d: Decoded): Pair<ByteArray, String?> {
        var cs = d.charset
        var bom = d.bom
        var note: String? = null
        var bytes = text.toByteArray(cs)
        val unicode = cs == Charsets.UTF_8 || cs == Charsets.UTF_16LE || cs == Charsets.UTF_16BE
        if (!unicode && String(bytes, cs) != text) {
            cs = UTF8
            bom = ByteArray(0)
            bytes = text.toByteArray(cs)
            note = "원래 인코딩에 없는 글자가 있어 UTF-8로 저장했어요"
        }
        return Pair(bom + bytes, note)
    }

    private fun strictDecode(b: ByteArray, cs: Charset): String? = try {
        cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b)).toString()
    } catch (e: CharacterCodingException) {
        null
    }

    // ---------------- 감지 점수 ----------------

    // 한국어 자막에서 아주 자주 나오는 음절
    private const val KO_COMMON =
        "이다는에가의을하고지한서도로어사들그나리기인있은아수대자것시보해일정여면라오게제주우부전만과마때같선안또무내미원신상동공구중개성며요네말않데장야걸거죠니뭐좀냥겠습합했없아줘봐와"

    // 중국어(간체 + 번체) 자막에서 아주 자주 나오는 글자
    private const val ZH_COMMON =
        "的一是不了人我在有他这中大来上国个到说们为子和你地出道也时年得就那要下以生会自着去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些与高意进把法此实回二理美点月明其种声全工己话儿者向情部正名定女问力机给等几很业最间新什打便位因重被走电四第门相次东政海口使教西再平真听世气信北少关并内加化由却代军产入先山五太水万市眼体别处总才场师书比住员九笑性通目华报立马命张活难神数件安表原车白应路期叫死常提感金何更反合放做系虽吗呢吧啊啦哦哪您咱嘛呀" +
            "這們來國個說為時會學對裡後麼沒於還發當開無見經頭兩長樣現將與進實點聲話兒問機給幾業間電門東關並內卻軍產萬體別處總場師書員華報馬張難數應嗎"

    private val koSet: Set<Int> = KO_COMMON.codePoints().toArray().toHashSet()
    private val zhSet: Set<Int> = ZH_COMMON.codePoints().toArray().toHashSet()

    /** lang: "ko" / "zh" / "ja". 높을수록 그 인코딩일 가능성이 크다 */
    private fun score(t: String, lang: String): Double {
        var n = 0
        var hits = 0
        var pen = 0
        var i = 0
        while (i < t.length) {
            val c = t.codePointAt(i)
            i += Character.charCount(c)
            if (c < 0x80 || Character.isWhitespace(c)) continue
            // 문장부호/기호 구간은 세지 않는다
            if ((c in 0x3000..0x303F) || (c in 0xFF00..0xFF60) || (c in 0x2000..0x206F)) continue
            n++
            // 틀린 인코딩으로 읽을 때 잘 나오는 글자: 반각 가타카나, 사용자 정의 문자, 깨진 글자, 기호 블록
            if ((c in 0xFF61..0xFF9F) || (c in 0xE000..0xF8FF) || c == 0xFFFD ||
                (c in 0x2460..0x27BF) || (c in 0x3130..0x318F && lang != "ko")
            ) pen++
            when (lang) {
                "ko" -> if (c in koSet) hits++
                "zh" -> if (c in zhSet) hits++
                "ja" -> if (c in 0x3040..0x30FF) hits++
            }
        }
        if (n == 0) return 0.0
        return (hits - 3.0 * pen) / n
    }
}
