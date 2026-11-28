package com.realestatenewsfinder.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** 스레드(Threads) 업로드용 도구: 작성자 모자이크, 캡처 분할, 복사/저장/공유 */
object ThreadsKit {
    const val THREADS_PACKAGE = "com.instagram.barcelona"
    private const val MAX_PIECES = 20

    /** 작성자 닉네임 영역을 모자이크 (원본은 건드리지 않고 새 비트맵 반환) */
    fun mosaic(src: Bitmap, rects: List<Rect>, block: Int = 14): Bitmap {
        if (rects.isEmpty()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint().apply { isFilterBitmap = false }
        rects.forEach { r0 ->
            // 닉네임 왼쪽에 붙은 작은 프로필 사진까지 가리도록 왼쪽으로 조금 넓힘
            val r = Rect(r0.left - minOf(r0.height(), 60), r0.top - 4, r0.right + 6, r0.bottom + 4)
            if (!r.intersect(0, 0, out.width, out.height) || r.width() < 2 || r.height() < 2) return@forEach
            val part = Bitmap.createBitmap(out, r.left, r.top, r.width(), r.height())
            val small = Bitmap.createScaledBitmap(part, maxOf(1, r.width() / block), maxOf(1, r.height() / block), true)
            canvas.drawBitmap(small, null, r, paint)
            part.recycle(); small.recycle()
        }
        return out
    }

    /**
     * 스레드용 세로 분할 위치 계산 (픽셀 없이 테스트 가능하도록 분리).
     * - 전체가 가로의 2배보다 짧으면 한 장, 여러 장이면 한 장이 가로의 약 1.6배
     * - 여러 장이면 길이를 고르게 나눔 (끝에 자투리 조각이 생기지 않게)
     * - 빈 줄(여백)에서만 자르고, 사진/영상 영역(avoid) 안에서는 자르지 않음
     * 반환: [시작, 자르는 위치들..., 끝]
     */
    fun cutPoints(blank: BooleanArray, w: Int, avoid: List<IntRange> = emptyList(), maxRatio: Float = 2f, idealRatio: Float = 1.6f): List<Int> {
        var top = 0
        var bottom = blank.size
        while (top < bottom && blank[top]) top++
        while (bottom > top && blank[bottom - 1]) bottom--
        top = maxOf(0, top - 8); bottom = minOf(blank.size, bottom + 8) // 위아래 여백은 조금만 남김
        val h = bottom - top
        val maxH = (w * maxRatio).toInt() // 한 장으로 둘 수 있는 최대 길이 (기본: 가로의 2배)
        if (h <= maxH) return listOf(top, bottom)

        // 사진 안에서는 자르지 않음. 단, 한 장에 다 안 들어가는 긴 사진(글 캡처 이미지 등)은 그 안의 여백에서 자를 수 있음
        fun inAvoid(y: Int) = avoid.firstOrNull { y > it.first && y < it.last && it.last - it.first < maxH }

        // 자를 수 있는 후보: 4px 이상 이어진 빈 줄의 가운데
        val cands = mutableListOf<Pair<Int, Int>>() // (위치, 여백 길이)
        var y = top
        while (y < bottom) {
            if (blank[y]) {
                val s = y
                while (y < bottom && blank[y]) y++
                val mid = (s + y) / 2
                if (y - s >= 4 && inAvoid(mid) == null) cands += mid to (y - s)
            } else y++
        }

        // 앞에서부터 한 장씩: 남은 길이가 한 장에 들어가면 끝, 아니면 목표 길이에 가장 가까운 여백에서 자름
        val cuts = mutableListOf(top)
        while (cuts.size < MAX_PIECES) {
            val prev = cuts.last()
            val rem = bottom - prev
            if (rem <= maxH) break
            val ideal = rem / Math.ceil(rem / (w * idealRatio.toDouble())).toFloat()
            val target = (prev + ideal).toInt()
            var cut = cands
                .filter { it.first - prev >= ideal * 0.45f && it.first - prev <= maxH && bottom - it.first >= ideal * 0.3f }
                .minByOrNull { Math.abs(it.first - target) - minOf(it.second, 80) * 2 }?.first
            if (cut == null) {
                // 쓸 수 있는 여백이 전혀 없을 때만: 사진 안이면 사진 경계로, 아니면 목표 지점에서 자름
                val r = inAvoid(target)
                cut = (if (r != null) listOf(r.first, r.last).filter { it - prev >= ideal * 0.3f && bottom - it >= ideal * 0.3f && it - prev <= maxH * 1.2f }
                    .minByOrNull { Math.abs(it - target) } else null) ?: target
            }
            cuts += cut
        }
        cuts += bottom
        return cuts
    }

    /**
     * 여백 줄인지: 가로로 훑었을 때 색이 바뀌는 지점(윤곽)이 거의 없으면 여백.
     * 회색 바탕 + 흰 카드, 양옆 테두리 선처럼 색이 몇 번만 바뀌는 줄은 여백으로 보고,
     * 글자·그림처럼 색이 여러 번 바뀌는 줄만 내용으로 봄.
     */
    fun isBlankRow(row: IntArray): Boolean {
        var edges = 0
        var x = 2
        while (x < row.size) {
            val p = row[x]
            val q = row[x - 2]
            val d = Math.abs(((p shr 16) and 255) - ((q shr 16) and 255)) +
                Math.abs(((p shr 8) and 255) - ((q shr 8) and 255)) + Math.abs((p and 255) - (q and 255))
            if (d > 40 && ++edges > 8) return false
            x += 2
        }
        return true
    }

    /** maxRatio·idealRatio를 키우면 한 장이 길어지고 장수가 줄어듦 (커뮤니티 글: 내용이 안 잘리는 게 우선) */
    fun split(src: Bitmap, avoid: List<IntRange> = emptyList(), maxRatio: Float = 2f, idealRatio: Float = 1.6f): List<Bitmap> {
        val w = src.width
        val row = IntArray(w)
        val blank = BooleanArray(src.height) { y ->
            src.getPixels(row, 0, w, 0, y, w, 1)
            isBlankRow(row)
        }
        val cuts = cutPoints(blank, w, avoid, maxRatio, idealRatio)
        return cuts.zipWithNext { a, b -> Bitmap.createBitmap(src, 0, a, w, b - a) }
    }

    /** 본문 영역(좌우는 전체 폭 유지). 없으면 전체 */
    fun cropRect(src: Bitmap, r: Rect?): Rect {
        val full = Rect(0, 0, src.width, src.height)
        if (r == null) return full
        val c = Rect(0, r.top - 12, src.width, r.bottom + 12)
        return if (c.intersect(full) && c.height() >= 100) c else full
    }

    // ---------- 복사 · 저장 · 공유 ----------

    fun copy(ctx: Context, label: String, text: String) {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "$label 복사했어요", Toast.LENGTH_SHORT).show()
    }

    /** 분할 이미지를 캐시에 JPEG로 저장 (공유용) */
    fun writeCache(ctx: Context, id: String, pieces: List<Bitmap>): List<File> {
        val dir = File(ctx.cacheDir, "export/${id.hashCode().toUInt()}").apply { deleteRecursively(); mkdirs() }
        return pieces.mapIndexed { i, b ->
            File(dir, "${i + 1}.jpg").also { f -> f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        }
    }

    /** 갤러리(사진/RealEstateNews)에 저장. 저장한 개수 반환 */
    fun saveToGallery(ctx: Context, files: List<File>, baseName: String): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        var n = 0
        files.forEachIndexed { i, f ->
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${baseName}_${i + 1}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RealEstateNews")
            }
            ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                n++
            }
        }
        return n
    }

    fun isThreadsInstalled(ctx: Context) =
        runCatching { ctx.packageManager.getPackageInfo(THREADS_PACKAGE, 0); true }.getOrDefault(false)

    /** 스레드 앱으로 사진 여러 장 + 본문 보내기 (스레드가 없으면 공유 목록) */
    fun shareToThreads(ctx: Context, files: List<File>, text: String) {
        val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 접근성 서비스에서 보낼 때
        }
        if (isThreadsInstalled(ctx)) {
            intent.setPackage(THREADS_PACKAGE)
            runCatching { ctx.startActivity(intent) }.onFailure {
                ctx.startActivity(Intent.createChooser(intent.setPackage(null), "공유할 앱 선택").apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
        } else {
            ctx.startActivity(Intent.createChooser(intent, "공유할 앱 선택").apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }
    }

    /**
     * '스레드에 올리기' 공통 동작. 접근성 자동 입력이 켜져 있고 '올리기 전 계정 확인'이 켜져 있으면
     * 먼저 스레드 계정을 확인·전환한 뒤 보냄. 아니면 바로 보냄.
     */
    fun upload(ctx: Context, files: List<File>, text: String) {
        val prefs = Prefs(ctx)
        val account = prefs.uploadAccount
        if (ThreadsAuto.isEnabled(ctx) && prefs.autoSwitchAccount && account.isNotBlank() && isThreadsInstalled(ctx) &&
            ThreadsAuto.startSwitch(ctx, account, files, text)
        ) return
        if (ThreadsAuto.isEnabled(ctx)) { ThreadsAuto.log = ""; ThreadsAuto.note("계정 확인 없이 바로 보냄 (사진 ${files.size}장)"); ThreadsAuto.start(text) }
        shareToThreads(ctx, files, text)
    }
}
