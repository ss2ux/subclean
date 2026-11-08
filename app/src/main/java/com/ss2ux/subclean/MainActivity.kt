package com.ss2ux.subclean

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * 파일 선택기: 읽기+쓰기 권한(덮어쓰기용)을 요청하는 OpenDocument.
 * [initialUriProvider] 가 null 이 아니면 그 위치를 먼저 열고, 없으면 Download 폴더부터 연다.
 * MIME 필터는 호출 쪽 launch(...) 인자로 넘긴다. 확장자 필터는 Android SAF 정책상 공식 지원되지 않는다.
 */
class OpenDocumentRw(
    private val initialUriProvider: () -> Uri? = { null }
) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
        val initial = initialUriProvider()
            ?: Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload")
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        return intent
    }
}


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme { AppScreen() }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content
    )
}
