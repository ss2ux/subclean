package com.ss2ux.subclean

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/** DeepL 번역 앱(안드로이드, DeepL SE)의 패키지 이름 */
const val DEEPL_PACKAGE = "com.deepl.mobiletranslator"

/**
 * 글을 DeepL 앱에 넘겨서 바로 번역하게 하는 함수를 만든다.
 * 글자를 선택했을 때 메뉴에 뜨는 'DeepL' 항목과 같은 방식(ACTION_PROCESS_TEXT)으로 보낸다.
 * 순서: 1) 글 선택 메뉴 방식  2) 공유 방식  3) 그냥 앱 열기(이때는 붙여 넣기)  4) 앱이 없으면 플레이스토어.
 *
 * 클립보드 복사는 이 함수를 부르기 '전에' 해 두어야 한다 (DeepL 앱이 앞에 뜬 뒤에는 이 앱이 클립보드에 쓸 수 없다).
 * 앱을 직접 여는 것이라 이 앱에는 인터넷 권한이 필요 없다.
 */
@Composable
fun rememberDeepLOpener(onError: (String) -> Unit): (String) -> Unit {
    val context = LocalContext.current
    val errorHandler = rememberUpdatedState(onError)
    return remember<(String) -> Unit>(context) {
        { text ->
            fun start(i: Intent): Boolean = try {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }

            try {
                val processText = Intent(Intent.ACTION_PROCESS_TEXT).apply {
                    type = "text/plain"
                    setPackage(DEEPL_PACKAGE)
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(DEEPL_PACKAGE)
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                if (!start(processText) && !start(send)) {
                    val launch = context.packageManager.getLaunchIntentForPackage(DEEPL_PACKAGE)
                    if (launch != null) {
                        errorHandler.value("DeepL에 글을 바로 넘기지 못했어요. 복사해 뒀으니 붙여 넣으세요")
                        start(launch)
                    } else {
                        errorHandler.value("DeepL 앱이 설치돼 있지 않아요. 플레이스토어를 열게요")
                        start(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$DEEPL_PACKAGE")))
                    }
                }
            } catch (e: Exception) {
                errorHandler.value("DeepL을 열 수 없어요: ${e.message ?: e}")
            }
        }
    }
}
