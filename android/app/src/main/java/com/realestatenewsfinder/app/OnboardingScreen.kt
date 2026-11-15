package com.realestatenewsfinder.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private fun batteryOk(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

private fun appDetails(ctx: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

/** 처음 실행 안내: 필요한 권한·설정을 순서대로 켜도록 도와줌 (설정 화면에서 다시 열 수 있음) */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    // 설정 화면에 다녀오면 상태를 다시 확인
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notifOk = remember(tick) { HotPostWorker.canNotify(ctx) && prefs.notifyEnabled }
    val battery = remember(tick) { batteryOk(ctx) }
    val access = remember(tick) { ThreadsAuto.isEnabled(ctx) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { prefs.notifyEnabled = true; HotPostWorker.schedule(ctx) } else ctx.startActivity(appDetails(ctx))
        tick++
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("처음 설정", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("아래 순서대로 켜면 준비 끝이에요", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(18.dp))

        Step(1, "새 소식 알림 켜기", "중요한 부동산 소식이 올라오면 알림으로 알려드려요.", notifOk, "알림 허용") {
            if (Build.VERSION.SDK_INT >= 33 && !HotPostWorker.canNotify(ctx)) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                prefs.notifyEnabled = true; HotPostWorker.schedule(ctx); tick++
            }
        }
        Step(2, "배터리 사용 제한 풀기", "절전 기능이 앱을 재우면 알림이 늦어져요. '허용'을 눌러주세요.", battery, "제한 풀기") {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }.onFailure { ctx.startActivity(appDetails(ctx)) }
        }
        Step(
            3, "제한된 설정 허용 (스레드 자동 입력용)",
            "① 먼저 4번을 눌러 회색으로 잠긴 '부동산뉴스파인더 · 스레드 자동 입력'을 한 번 터치\n" +
                "② 이 버튼으로 앱 정보 화면을 열고 오른쪽 위 ⋮ → '제한된 설정 허용'\n" +
                "(⋮ 메뉴가 없으면 이미 허용된 거예요)",
            access, "앱 정보 열기",
        ) { ctx.startActivity(appDetails(ctx)) }
        Step(
            4, "접근성에서 스레드 자동 입력 켜기",
            "설치된 앱 → '부동산뉴스파인더 · 스레드 자동 입력'을 켜주세요. 스레드 앱 화면에서만 동작하고, 게시 버튼은 누르지 않아요.",
            access, "접근성 설정 열기",
        ) { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        Text(
            "3·4번은 내보내기에서 '스레드에 올리기' 자동 입력을 쓸 때만 필요해요. 나중에 설정 → '처음 설정 다시 보기'에서 할 수 있어요.",
            fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
        )
        GradientButton(if (notifOk && battery && access) "설정 완료! 시작하기" else "시작하기", null, onClick = { prefs.onboardingDone = true; onDone() })
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Step(no: Int, title: String, desc: String, ok: Boolean, action: String, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.padding(vertical = 5.dp).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)).padding(14.dp),
    ) {
        Box(Modifier.size(30.dp).background(if (ok) Color(0xFF22B573) else primary, CircleShape), contentAlignment = Alignment.Center) {
            if (ok) Icon(Icons.Filled.Check, "완료", tint = Color.White, modifier = Modifier.size(18.dp))
            else Text("$no", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (ok) Text("완료됨", color = Color(0xFF22B573), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            else Button(onClick = onClick, shape = CircleShape) { Text(action, fontWeight = FontWeight.Bold) }
        }
    }
}
