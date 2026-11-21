package com.realestatenewsfinder.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AuctionRed = Color(0xFFE5484D)
private val UpColor = Color(0xFFE5484D)
private val DownColor = Color(0xFF2563EB)
private val SentGreen = Color(0xFF0E7C3E)

@Composable
private fun MyCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.fillMaxWidth().shadow(4.dp, shape, ambientColor = Color(0x262563EB), spotColor = Color(0x262563EB)).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        content = content,
    )
}

@Composable
private fun Section(text: String) = Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(start = 6.dp, top = 6.dp))

@Composable
private fun Tag(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.12f), CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

/** 겹쳐 띄우는 화면의 머리: 뒤로가기 + 제목 */
@Composable
private fun OverlayTop(title: String, sub: String, onBack: () -> Unit) {
    Row(Modifier.statusBarsPadding().fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AuctionCard(a: MyAuction, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MyCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(if (a.gone) "목록에서 빠짐" else a.dday().ifBlank { "진행" }, if (a.gone) muted else AuctionRed)
            Spacer(Modifier.width(8.dp))
            Text(listOfNotNull(a.complexName, a.area?.let { "${DealFmt.area(it)}㎡" }).joinToString(" "), fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
        }
        Text("최저가 ${My.won(a.minPrice)} · 감정가의 ${a.minRate}%", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text("감정가 ${My.won(a.appraisal)} · 유찰 ${a.failCount}회", fontSize = 13.sp, color = muted)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("매각기일 ${a.saleDate} ${a.saleTime} · ${a.caseNo}", fontSize = 12.sp, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (a.post != null && UploadLog.sent.containsKey("report-" + a.post.id)) Text("  ✓ 올림", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SentGreen)
        }
    }
}

/** 내 단지 탭: ① 메인 단지(실거래 분석) ② 경매정보(메인 단지의 법원 경매 물건) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen(
    data: MyData?, error: String?, loading: Boolean, onRefresh: () -> Unit,
    mode: Int, onMode: (Int) -> Unit, onComplex: (MyComplex) -> Unit, onAuction: (MyAuction) -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text("내 단지", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (mode == 1) "메인 단지가 경매로 나오면 알림을 받고 ${Platform.current.label}용으로 올려요" else "깊게 보는 단지의 실거래 분석을 보고 ${Platform.current.label}용으로 올려요",
                fontSize = 13.sp, color = muted,
            )
            Spacer(Modifier.height(8.dp))
            PlatformBar()
            Spacer(Modifier.height(10.dp))
            PillRow {
                ChoicePill("메인 단지", mode == 0) { onMode(0) }
                ChoicePill("경매정보" + (data?.live?.size?.takeIf { it > 0 }?.let { " $it" } ?: ""), mode == 1) { onMode(1) }
            }
        }
        PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                if (error != null) item { Text("자료를 받지 못했어요: $error", fontSize = 13.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(6.dp)) }
                if (data == null) item { EmptyText(if (error == null) "불러오는 중…" else "아래로 당겨 다시 시도하세요.") }
                if (data != null && mode == 0) {
                    items(data.complexes, key = { "c" + it.id }) { c ->
                        MyCard(onClick = { onComplex(c) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Tag(c.kindLabel, MaterialTheme.colorScheme.primary)
                                if (c.auctionCount > 0) { Spacer(Modifier.width(6.dp)); Tag("경매 ${c.auctionCount}건", AuctionRed) }
                                Spacer(Modifier.width(8.dp))
                                Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = muted)
                            }
                            if (c.hasData) {
                                Text("최근 1년 매매 ${c.trade12}건 · 전세 ${c.jeonse12}건 · 월세 ${c.monthly12}건", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
                                c.areas.filter { it.tradeCount > 0 }.take(4).forEach { a ->
                                    Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${a.area}㎡", fontSize = 14.sp, color = muted, modifier = Modifier.width(52.dp))
                                        Text(DealFmt.manwon(a.tradeMedian), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        a.changePct?.let { p -> Text((if (p > 0) "▲ " else if (p < 0) "▼ " else "") + "${kotlin.math.abs(p)}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (p > 0) UpColor else if (p < 0) DownColor else muted) }
                                    }
                                }
                            } else Text(c.insights.firstOrNull().orEmpty(), fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    item { Text("면적별 금액은 최근 1년 매매 중간값, ▲▼는 1년 전 대비예요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(horizontal = 6.dp)) }
                }
                if (data != null && mode == 1) {
                    if (data.auctions.isEmpty()) item {
                        Text("지금 진행 중인 메인 단지 경매 물건이 없어요.\n새 물건이 나오면 알림으로 알려드려요.", fontSize = 14.sp, lineHeight = 20.sp, color = muted, modifier = Modifier.padding(horizontal = 6.dp, vertical = 24.dp))
                    }
                    items(data.auctions, key = { "a" + it.id }) { a -> AuctionCard(a) { onAuction(a) } }
                }
                if (data != null) {
                    items(data.notes, key = { "n$it" }) { n -> Text("※ $n", fontSize = 12.sp, lineHeight = 17.sp, color = muted, modifier = Modifier.padding(horizontal = 6.dp)) }
                    item {
                        Text(
                            "자료: 국토교통부 실거래가 공개시스템, 법원경매정보(${data.court}) · 확인 ${ago(data.time)} · 3시간마다 새로 받아요",
                            fontSize = 12.sp, lineHeight = 17.sp, color = muted, modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DealLine(d: MyDeal) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(d.date.drop(2), fontSize = 13.sp, color = muted, modifier = Modifier.width(72.dp))
        Text("${DealFmt.area(d.area)}㎡ · ${d.floor}층" + if (d.type == "trade") "" else if (d.type == "monthly") " · 월세" else " · 전세", fontSize = 13.sp, color = muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(d.amount() + if (d.canceled) " (취소)" else "", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (d.canceled) muted else MaterialTheme.colorScheme.onSurface)
    }
}

/** 메인 단지 상세: 분석 요점 → 면적별 표 → 경매 물건 → 최근 실거래. 아래 버튼으로 실거래 상세·알림, 올리기 */
@Composable
fun MyComplexScreen(c: MyComplex, auctions: List<MyAuction>, onBack: () -> Unit, onDeals: (DealTarget) -> Unit, onShare: (Report) -> Unit, onAuction: (MyAuction) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OverlayTop(c.name, listOfNotNull(c.region, c.umdNm.ifBlank { null }, c.kindLabel, c.buildYear?.let { "${it}년" }).joinToString(" · "), onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                MyCard {
                    Text("분석 요점", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    c.insights.forEach { Text("· $it", fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            if (c.areas.isNotEmpty()) {
                item { Section("면적별 (최근 1년)") }
                items(c.areas, key = { "ar" + it.area }) { a ->
                    MyCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("전용 ${a.area}㎡", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            a.changePct?.let { p -> Text("1년 전보다 " + (if (p > 0) "+" else "") + "$p%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (p > 0) UpColor else if (p < 0) DownColor else muted) }
                        }
                        if (a.tradeCount > 0) {
                            Text("매매 중간값 ${DealFmt.manwon(a.tradeMedian)} · ${a.tradeCount}건", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                            Text("${DealFmt.manwon(a.tradeMin)} ~ ${DealFmt.manwon(a.tradeMax)}" + if (a.highPrice > 0) " · 3년 최고 ${DealFmt.manwon(a.highPrice)} (${a.highDate})" else "", fontSize = 13.sp, color = muted)
                        } else Text("최근 1년 매매 신고 없음", fontSize = 13.sp, color = muted, modifier = Modifier.padding(top = 4.dp))
                        if (a.jeonseCount > 0) Text("전세 중간값 ${DealFmt.manwon(a.jeonseMedian)} · ${a.jeonseCount}건" + (a.jeonseRate?.let { " · 전세가율 $it%" } ?: ""), fontSize = 13.sp, modifier = Modifier.padding(top = 3.dp))
                        if (a.monthlyCount > 0) Text("월세 중간값 보증금 ${DealFmt.manwon(a.monthlyDeposit)} / 월 ${a.monthlyRent}만 · ${a.monthlyCount}건", fontSize = 13.sp, color = muted)
                    }
                }
            }
            if (auctions.isNotEmpty()) {
                item { Section("경매 물건") }
                items(auctions, key = { "au" + it.id }) { a -> AuctionCard(a) { onAuction(a) } }
            }
            if (c.recentTrades.isNotEmpty()) item {
                Section("최근 매매")
                Spacer(Modifier.height(8.dp))
                MyCard { c.recentTrades.forEach { DealLine(it) } }
            }
            if (c.recentRents.isNotEmpty()) item {
                Section("최근 전월세")
                Spacer(Modifier.height(8.dp))
                MyCard { c.recentRents.forEach { DealLine(it) } }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            c.target?.let { t -> OutlinedButton(onClick = { onDeals(t) }, modifier = Modifier.weight(1f).height(50.dp)) { Text("실거래 상세·알림", fontWeight = FontWeight.Bold) } }
            c.post?.let { p -> Button(onClick = { onShare(p) }, modifier = Modifier.weight(1f).height(50.dp)) { Text("${Platform.current.label}에 올리기", fontWeight = FontWeight.Bold) } }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, bold: Boolean = false) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
    }
}

/** 경매 물건 상세: 가격·일정 → 물건 → 권리 관계 → 기일 내역 → 감정평가 요점. 아래 버튼으로 올리기 */
@Composable
fun AuctionScreen(a: MyAuction, onBack: () -> Unit, onShare: (Report) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OverlayTop(listOfNotNull(a.complexName, a.area?.let { "${DealFmt.area(it)}㎡" }).joinToString(" ") + " 경매", "${a.court} ${a.dept} · ${a.caseNo}", onBack)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                MyCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(if (a.gone) "목록에서 빠짐" else a.dday().ifBlank { "진행" }, if (a.gone) muted else AuctionRed)
                        Spacer(Modifier.width(8.dp))
                        Text("매각기일 ${a.saleDate} ${a.saleTime}", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                    Text("최저가 ${My.won(a.minPrice)}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp))
                    Text("감정가 ${My.won(a.appraisal)}의 ${a.minRate}% · 유찰 ${a.failCount}회", fontSize = 14.sp, color = muted)
                    if (a.gone) Text("법원 목록에서 빠진 물건이에요 (매각·취하·기일 변경 등). 진행 상황은 법원경매정보에서 확인하세요.", fontSize = 12.sp, lineHeight = 17.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                MyCard {
                    Text("물건", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    InfoRow("소재지", a.address)
                    InfoRow("용도", a.usage)
                    InfoRow("전용면적", a.area?.let { "${DealFmt.area(it)}㎡" }.orEmpty())
                    InfoRow("대지권", a.landShare)
                    InfoRow("사건", listOf(a.caseName, a.receivedDate.takeIf { it.isNotBlank() }?.let { "$it 접수" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
                    InfoRow("청구금액", if (a.claim > 0) My.won(a.claim) else "")
                    InfoRow("매각 장소", a.place)
                    InfoRow("매각결정", a.decisionDate)
                    InfoRow("담당", listOf(a.dept, a.tel).filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
            item {
                MyCard {
                    Text("권리 관계 (매각물건명세서)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AuctionRed)
                    InfoRow("인수 권리", a.rights.ifBlank { "매수인이 인수하는 권리 기재 없음" }, bold = a.rights.isNotBlank())
                    InfoRow("최선순위", a.topPriority)
                    InfoRow("명세서 비고", a.specRemark)
                    InfoRow("물건 비고", a.remark)
                    Text("입찰 전 매각물건명세서·현황조사서·등기부를 꼭 확인하세요.", fontSize = 12.sp, lineHeight = 17.sp, color = muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            if (a.schedule.isNotEmpty()) item {
                MyCard {
                    Text("기일 내역", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    a.schedule.forEach { s ->
                        InfoRow(s.date.drop(2), listOfNotNull(s.kind, s.time.ifBlank { null }, if (s.minPrice > 0) "최저가 ${My.won(s.minPrice)}" else null, s.result.ifBlank { null }, if (s.soldPrice > 0) "매각가 ${My.won(s.soldPrice)}" else null).joinToString(" · "))
                    }
                }
            }
            if (a.appraisalNotes.isNotEmpty()) item {
                MyCard {
                    Text("감정평가서 요점", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    a.appraisalNotes.forEach { Text("· $it", fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
            item { Text("자료: 법원경매정보. 일정과 가격은 바뀔 수 있어요.", fontSize = 12.sp, color = muted, modifier = Modifier.padding(horizontal = 6.dp)) }
        }
        a.post?.let { p ->
            Button(onClick = { onShare(p) }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).height(50.dp)) {
                Text("${Platform.current.label}에 올리기", fontWeight = FontWeight.Bold)
            }
        }
    }
}
