package com.ss2ux.subclean

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/**
 * 원본에 덮어쓰기 직전, 그 파일이 있는 폴더 안에 `원본 자막` 폴더를 만들고 원본을 한 번만 저장한다.
 * 확장자(.srt .vtt .lrc .txt)와 상관없이 덮어쓰는 파일이면 모두 같은 방식으로 백업한다.
 *
 * 파일 선택기로 연 파일은 "그 파일 하나"에 대한 접근 권한만 있어서 같은 폴더에 새 폴더를 만들 수 없다.
 * 그래서 이 기능에만 '모든 파일 접근'(MANAGE_EXTERNAL_STORAGE) 권한을 쓴다. 설정 탭에서 끄고 켤 수 있다.
 */
object OriginalBackup {
    const val DIR_NAME = "원본 자막"

    /** 예전 버전이 쓰던 백업 폴더 이름. 새로 만들지는 않고, 이미 백업이 있는지 확인할 때만 쓴다 */
    private const val LEGACY_DIR_NAME = "vtt"

    class Result(val ok: Boolean, val note: String? = null, val error: String? = null)

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasPermission(): Boolean = supported() && Environment.isExternalStorageManager()

    /** '모든 파일 접근' 허용 화면을 연다 (이 앱 전용 화면, 없으면 전체 목록) */
    fun openPermissionSettings(context: Context) {
        if (!supported()) return
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 같은 폴더의 `원본 자막/<파일이름>` (예전 버전의 `vtt/<파일이름>` 포함) 이 이미 있으면 아무것도 하지 않는다
     * (1회만 저장, 처음 원본을 지키기 위해).
     * 실패하면 ok=false 와 이유를 돌려주고, 호출한 쪽은 덮어쓰기를 하지 않는다.
     * [readOriginal] 은 백업이 실제로 필요할 때만 불린다 (덮어쓰기 직전의 파일 내용).
     */
    fun backupOnce(context: Context, uri: Uri, readOriginal: () -> ByteArray?): Result {
        if (!supported()) {
            return Result(false, error = "원본 백업은 안드로이드 11 이상에서만 돼요. 설정 탭에서 백업을 끄면 바로 덮어써요")
        }
        if (!hasPermission()) {
            return Result(false, error = "원본 백업에 '모든 파일 접근' 권한이 필요해서 덮어쓰지 않았어요. 설정 탭에서 허용하거나 백업을 꺼 주세요")
        }
        val file = resolveFile(context, uri)
            ?: return Result(false, error = "원본 파일 위치를 알 수 없어 백업하지 못해서 덮어쓰지 않았어요. 설정 탭에서 백업을 끄면 바로 덮어써요")
        val dir = file.parentFile
            ?: return Result(false, error = "원본이 있는 폴더를 알 수 없어 백업하지 못해서 덮어쓰지 않았어요")
        // 이미 백업 폴더 안의 파일이면 다시 백업하지 않는다
        if (dir.name == DIR_NAME || dir.name == LEGACY_DIR_NAME) return Result(true)
        val backupDir = File(dir, DIR_NAME)
        val target = File(backupDir, file.name)
        if (target.exists()) return Result(true)         // 1회만
        if (File(File(dir, LEGACY_DIR_NAME), file.name).exists()) return Result(true)   // 예전 vtt 폴더에 이미 백업돼 있으면 처음 원본이 거기 있다
        val data = readOriginal()
            ?: return Result(false, error = "원본을 읽지 못해 백업하지 못해서 덮어쓰지 않았어요")
        return try {
            if (!backupDir.isDirectory && !backupDir.mkdirs()) {
                Result(false, error = "'$DIR_NAME' 폴더를 만들지 못해서 덮어쓰지 않았어요")
            } else {
                // 임시 파일에 다 쓴 뒤 이름을 바꿔서, 쓰다 끊긴 반쪽 백업이 '백업 있음'으로 남지 않게 한다
                val tmp = File(backupDir, file.name + ".tmp")
                tmp.writeBytes(data)
                if (tmp.renameTo(target)) {
                    Result(true, note = "원본을 '$DIR_NAME' 폴더에 백업했어요")
                } else {
                    tmp.delete()
                    Result(false, error = "백업 파일을 저장하지 못해서 덮어쓰지 않았어요")
                }
            }
        } catch (e: Exception) {
            Result(false, error = "백업 실패(${e.message ?: e})로 덮어쓰지 않았어요")
        }
    }

    /** 파일 선택기가 준 주소에서 실제 파일 경로를 찾는다. 못 찾으면 null */
    private fun resolveFile(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }?.takeIf { it.isFile }
        try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                when (uri.authority) {
                    "com.android.externalstorage.documents" -> {
                        // primary:Download/a.srt  또는  1234-ABCD:자막/a.srt
                        val i = docId.indexOf(':')
                        if (i > 0) {
                            val vol = docId.substring(0, i)
                            val rel = docId.substring(i + 1)
                            val root = if (vol.equals("primary", true)) Environment.getExternalStorageDirectory()
                            else File("/storage/$vol")
                            File(root, rel).takeIf { it.isFile }?.let { return it }
                        }
                    }
                    "com.android.providers.downloads.documents" -> {
                        if (docId.startsWith("raw:")) {
                            File(docId.removePrefix("raw:")).takeIf { it.isFile }?.let { return it }
                        }
                        val id = docId.toLongOrNull() ?: docId.substringAfter(':', "").toLongOrNull()
                        if (id != null) {
                            queryData(context, MediaStore.Files.getContentUri("external", id))?.let { return it }
                        }
                    }
                    "com.android.providers.media.documents" -> {
                        val id = docId.substringAfter(':', "").toLongOrNull()
                        if (id != null) {
                            queryData(context, MediaStore.Files.getContentUri("external", id))?.let { return it }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return queryData(context, uri)
    }

    @Suppress("DEPRECATION")
    private fun queryData(context: Context, uri: Uri): File? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { File(it) }?.takeIf { it.isFile } else null
        }
    } catch (e: Exception) {
        null
    }
}
