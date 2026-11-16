// 청약·공공분양 공고 수집: LH 청약플러스 공고 목록, 청약홈 분양정보 (둘 다 목록 HTML을 읽음. 키 불필요)
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';

const KST = 9 * 3600_000;
const DAY = 24 * 3600_000;
const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();

/** "2026.11.16" / "2026-11-16" → 그날 낮 12시(KST) 시각. 없으면 null */
function noon(text) {
  const m = String(text ?? '').match(/(20\d{2})[.\-/](\d{1,2})[.\-/](\d{1,2})/);
  return m ? Date.UTC(+m[1], +m[2] - 1, +m[3], 12) - KST : null;
}
const md = (t) => { const d = new Date(t + KST); return `${d.getUTCMonth() + 1}/${d.getUTCDate()}`; };
const todayStart = (now) => Math.floor((now + KST) / DAY) * DAY - KST;

/**
 * LH 청약플러스 공고 목록 (분양·임대). 마감 전 공고만.
 * 제목: "[분양주택] 의정부우정 A-1블록 공공분양주택 … (경기도, ~12/10 마감)"
 */
export function parseLhList(html, source, now = Date.now()) {
  const $ = cheerio.load(html);
  const out = [];
  const seen = new Set();
  $('tbody tr').each((_, el) => {
    const tr = $(el);
    const a = tr.find('a.wrtancInfoBtn').first();
    const id = a.attr('data-id1');
    if (!id || seen.has(id)) return;
    const span = a.find('span').first().clone();
    span.find('em').remove(); // "1일전" 표시 제거
    const name = clean(span.text() || a.text());
    const type = clean(tr.find('td.cate.col1').first().text());
    const region = clean(tr.find('td.cate.col2').first().text());
    const status = clean(tr.find('td.stt').first().text());
    if (!name) return;
    if (source.types && !source.types.test(type)) return;
    const dates = tr.find('td').map((__, td) => clean($(td).text())).get().filter((t) => /^20\d{2}\.\d{1,2}\.\d{1,2}$/.test(t));
    const posted = noon(dates[0]);
    const due = noon(dates[1]);
    if (due != null && due + DAY / 2 < todayStart(now)) return; // 마감 지남
    if (/마감|종료/.test(status) && !/공고중|접수중/.test(status)) return;
    seen.add(id);
    const q = new URLSearchParams({ mi: String(source.mi ?? 1026), panId: id, ccrCnntSysDsCd: a.attr('data-id2') ?? '', uppAisTpCd: a.attr('data-id3') ?? '', aisTpCd: a.attr('data-id4') ?? '' });
    const tail = [region, due != null ? `~${md(due)} 마감` : ''].filter(Boolean).join(', ');
    out.push({
      id: `${source.id}:${id}`, source: source.id,
      title: `[${type || '공고'}] ${name}${tail ? ` (${tail})` : ''}`,
      url: `https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancInfo.do?${q}`,
      views: null, comments: null, likes: null,
      publishedAt: posted != null ? Math.min(posted, now) : null,
      outlet: 'LH 청약플러스',
      summary: clean([type, region, dates[0] && `게시 ${dates[0]}`, dates[1] && `마감 ${dates[1]}`, status].filter(Boolean).join(' · ')),
    });
  });
  return out.slice(0, source.limit ?? 40);
}

/**
 * 청약홈 분양정보 목록 (APT 분양 / 무순위·잔여세대). 청약 접수가 끝나지 않은 공고만.
 * 제목: "[청약] 호반써밋 초량 (부산·민영) 11/23~11/25 접수"
 */
export function parseApplyhome(html, source, now = Date.now()) {
  const $ = cheerio.load(html);
  const out = [];
  const seen = new Set();
  $('tbody tr[data-pbno]').each((_, el) => {
    const tr = $(el);
    const pbno = tr.attr('data-pbno');
    const hmno = tr.attr('data-hmno') || pbno;
    const name = clean(tr.attr('data-honm') || tr.find('td.txt_l').first().text());
    if (!pbno || !name || seen.has(pbno)) return;
    const cells = tr.find('td').map((__, td) => clean($(td).text())).get();
    const region = cells[0] ?? '';
    const kind = cells.slice(1, 4).filter((c) => c && c.length <= 8 && !c.includes(name)).slice(0, 2); // 민영/국민, 분양주택 등
    const single = cells.filter((c) => /^20\d{2}-\d{2}-\d{2}$/.test(c));
    const range = cells.find((c) => /^20\d{2}-\d{2}-\d{2}\s*~\s*20\d{2}-\d{2}-\d{2}$/.test(c));
    const posted = noon(single[0]);
    const [from, to] = range ? range.split('~').map((x) => noon(x)) : [null, null];
    if (to != null && to + DAY / 2 < todayStart(now)) return; // 접수 끝남
    if (to == null && posted != null && now - posted > 21 * DAY) return;
    seen.add(pbno);
    const builder = cells.find((c) => /\(주\)|건설|개발|조합|공사|주식회사/.test(c) && c !== name) ?? '';
    const when = from != null && to != null ? `${md(from)}~${md(to)} 접수` : '';
    out.push({
      id: `${source.id}:${pbno}`, source: source.id,
      title: `[${source.label ?? '청약'}] ${name} (${[region, kind[0]].filter(Boolean).join('·')})${when ? ` ${when}` : ''}`,
      url: `https://www.applyhome.co.kr/ai/aia/${source.detail}?houseManageNo=${hmno}&pblancNo=${pbno}`,
      views: null, comments: null, likes: null,
      publishedAt: posted != null ? Math.min(posted, now) : null,
      outlet: '청약홈',
      summary: clean([kind.join(' '), single[0] && `모집공고 ${single[0]}`, range && `청약 ${range}`, single[1] && `당첨자 발표 ${single[1]}`, builder].filter(Boolean).join(' · ')),
    });
  });
  return out.slice(0, source.limit ?? 40);
}

export async function fetchLh(source) {
  const html = await fetchHtml(`https://apply.lh.or.kr/lhapply/apply/wt/wrtanc/selectWrtancList.do?mi=${source.mi ?? 1026}`, { cookies: true });
  const rows = (html.match(/wrtancInfoBtn/g) || []).length;
  const posts = parseLhList(html, source);
  console.log(`[apply] ${source.id}: 공고 ${rows}건 중 ${posts.length}건`);
  if (rows === 0) throw new Error('공고 목록을 찾지 못했습니다 (사이트 구조 변경 가능성)');
  return posts;
}

export async function fetchApplyhome(source) {
  const html = await fetchHtml(`https://www.applyhome.co.kr/ai/aia/${source.list}`, { cookies: true });
  const rows = (html.match(/data-pbno=/g) || []).length;
  const posts = parseApplyhome(html, source);
  console.log(`[apply] ${source.id}: 공고 ${rows}건 중 ${posts.length}건${rows === 0 ? ` (앞부분: ${html.slice(0, 200).replace(/\s+/g, ' ')})` : ''}`);
  if (rows === 0 && !source.allowEmpty) throw new Error('분양 목록을 찾지 못했습니다 (사이트 구조 변경 가능성)');
  return posts;
}

// ---------- 상세 페이지에서 주요 정보 뽑기 (목록만으로는 내용이 부족해서) ----------

/** 만원 단위 금액 → "5억6,320만원" */
export function eok(manwon) {
  const n = Math.round(manwon);
  if (!Number.isFinite(n) || n <= 0) return '';
  const e = Math.floor(n / 10000);
  const m = n % 10000;
  return `${e ? `${e}억` : ''}${m ? `${m.toLocaleString('ko-KR')}만` : ''}원`;
}
const num = (t) => { const v = parseFloat(String(t ?? '').replace(/,/g, '')); return Number.isFinite(v) ? v : null; };
const short = (d) => { const m = String(d).match(/(\d{4})[-.](\d{1,2})[-.](\d{1,2})/); return m ? `${+m[2]}/${+m[3]}` : String(d); };

function tableByCaption($, re) {
  return $('table').filter((_, t) => re.test($(t).find('caption').text())).first();
}

/** 청약홈 APT 분양 상세 → { details: ["항목: 내용", …], summary } */
export function parseApplyhomeDetail(html) {
  const $ = cheerio.load(html);
  $('script,style,noscript').remove();
  const text = clean($('body').text());
  const details = [];
  // "항목 | 값" 줄에서 값 찾기 (항목 칸이 th일 수도 td일 수도 있음)
  const pair = (label) => {
    let v = '';
    $('tr').each((_, tr) => {
      const c = $(tr).children('th,td').map((__, x) => clean($(x).text())).get();
      if (!v && c[0] === label && c[1]) v = c[1];
    });
    return v;
  };
  const loc = pair('공급위치');
  if (loc) details.push(`공급위치: ${loc}`);

  // 공급대상 표의 '계' 줄: 일반 | 특별 | 계
  let total = null;
  $('table').filter((_, t) => /공급대상/.test($(t).find('caption').text()) && !/특별공급/.test($(t).find('caption').text())).first().find('tr').each((_, tr) => {
    const c = $(tr).find('th,td').map((__, x) => clean($(x).text())).get();
    if (c[0] === '계' && c.length >= 4) total = [null, c[1], c[2], c[3]];
  });
  const scale = pair('공급규모');
  if (total) details.push(`공급규모: ${total[3]}세대 (일반 ${total[1]} · 특별 ${total[2]})`);
  else if (scale) details.push(`공급규모: ${scale}`);

  // 주택형별 분양가 (최고가 기준, 만원): 전용면적(정수)별로 묶어 범위로
  const byArea = new Map();
  tableByCaption($, /공급금액/).find('tr').each((_, tr) => {
    const c = $(tr).find('td').map((__, td) => clean($(td).text())).get();
    const area = parseInt(c[0], 10);
    const price = num(c[1]);
    if (!Number.isFinite(area) || price == null || price < 1000) return;
    const cur = byArea.get(area) ?? [price, price];
    byArea.set(area, [Math.min(cur[0], price), Math.max(cur[1], price)]);
  });
  // 무순위·잔여세대 공고: "주택형 | 공급세대수 | 분양가(또는 비고)" 표
  const units = new Map();
  if (!byArea.size) {
    $('tr').each((_, tr) => {
      const c = $(tr).find('td').map((__, td) => clean($(td).text())).get();
      if (!/^\d{2,3}\.\d+/.test(c[0] ?? '')) return;
      const area = parseInt(c[0], 10);
      const n = num(c[1]);
      if (n != null && n > 0 && n < 100000) units.set(area, (units.get(area) ?? 0) + n);
      const price = /^[\d,]+$/.test(c[2] ?? '') ? num(c[2]) : null;
      if (price == null || price < 1000) return;
      const cur = byArea.get(area) ?? [price, price];
      byArea.set(area, [Math.min(cur[0], price), Math.max(cur[1], price)]);
    });
    const u = [...units.entries()].sort((a, b) => a[0] - b[0]);
    if (u.length) details.push(`주택형: ${u.slice(0, 5).map(([a, n]) => `${a}㎡ ${n}세대`).join(' · ')}${u.length > 5 ? ' 외' : ''}`);
    if (u.length && !byArea.size && /공급금액\s*:\s*사업주체 문의/.test(text)) details.push('분양가: 사업주체 문의 (모집공고문 확인)');
  }
  const areas = [...byArea.entries()].sort((a, b) => a[0] - b[0]);
  // 종류가 많으면 가장 작은 것, 59·84 같은 대표 면적, 가장 큰 것 위주로 4개까지
  const picked = areas.length <= 4 ? areas : [areas[0], ...areas.filter(([a]) => a === 59 || a === 84), areas.at(-1)].filter((v, i, arr) => arr.indexOf(v) === i).slice(0, 4).sort((a, b) => a[0] - b[0]);
  for (const [a, [lo, hi]] of picked) details.push(`${a}㎡ 분양가: ${lo === hi ? eok(lo) : `${eok(lo).replace(/원$/, '')}~${eok(hi)}`}`);

  const sp = text.match(/특별공급\s*(20\d\d-\d\d-\d\d)/)?.[1];
  const r1 = text.match(/1순위\s*(20\d\d-\d\d-\d\d)/)?.[1];
  const r2 = text.match(/2순위\s*(20\d\d-\d\d-\d\d)/)?.[1];
  const recv = !sp && !r1 && text.match(/청약접수\s*(20\d\d-\d\d-\d\d)\s*~\s*(20\d\d-\d\d-\d\d)/);
  if (recv) details.push(`청약접수: ${recv[1] === recv[2] ? short(recv[1]) : `${short(recv[1])}~${short(recv[2])}`}`);
  const tel = text.match(/분양사무실\s*\(([\d-]{7,14})\)/)?.[1];
  const sched = [sp && `특별공급 ${short(sp)}`, r1 && `1순위 ${short(r1)}`, r2 && `2순위 ${short(r2)}`].filter(Boolean);
  if (sched.length) details.push(`청약일정: ${sched.join(' · ')}`);
  const win = text.match(/당첨자\s*발표일?\s*(20\d\d-\d\d-\d\d)/)?.[1];
  const contract = text.match(/계약일\s*(20\d\d-\d\d-\d\d)\s*~\s*(20\d\d-\d\d-\d\d)/);
  if (win) details.push(`당첨자 발표: ${short(win)}${contract ? ` · 계약 ${short(contract[1])}~${short(contract[2])}` : ''}`);
  const movein = text.match(/입주예정월\s*:\s*([\d.]+)/)?.[1];
  if (movein) details.push(`입주예정: ${movein.replace(/^(\d{4})\.(\d{1,2})$/, (_, y, m) => `${y}년 ${+m}월`)}`);
  const etc = tableByCaption($, /기타사항/).find('tr').eq(1).find('td').map((_, td) => clean($(td).text())).get();
  if (etc[0] || etc[1]) details.push(`시행·시공: ${[etc[0], etc[1]].filter(Boolean).join(' / ')}`);
  if (tel && units.size) details.push(`문의: ${tel}`);
  const special = text.match(/특이사항\s*:\s*([^*]{2,30}?)\s*\*/)?.[1];
  if (special) details.push(`특이사항: ${clean(special)}`);
  return { details: details.slice(0, 9) };
}

/** LH 청약플러스 공고 상세 → { details, summary } */
export function parseLhDetail(html) {
  const $ = cheerio.load(html);
  $('script,style,noscript').remove();
  const text = clean($('body').text());
  const details = [];
  const loc = text.match(/소재지\s*:\s*(.+?)\s*(?:전용면적|총\s?세대수|난방방식)/)?.[1];
  if (loc) details.push(`소재지: ${clean(loc).slice(0, 50)}`);
  const complexes = new Set([...text.matchAll(/소재지\s*:\s*(.+?)\s*(?:전용면적|총\s?세대수|난방방식)/g)].map((m) => clean(m[1]))).size;
  if (complexes > 1) details.push(`대상 단지: ${complexes}곳`);

  // 주택형 표: 분양은 평균분양가격, 임대는 임대보증금·월임대료
  let supply = 0;
  const types = [];
  $('table').filter((_, t) => /주택형 안내/.test($(t).find('caption').text())).each((_, t) => {
    const head = $(t).find('tr').first().children('th,td').map((__, th) => clean($(th).text())).get();
    const col = (re) => head.findIndex((h) => re.test(h));
    const iArea = col(/전용면적/); const iNow = col(/금회공급/); const iPrice = col(/평균분양가격/); const iDep = col(/임대보증금/); const iRent = col(/월임대료/);
    $(t).find('tbody tr').each((__, tr) => {
      // 줄 맨 앞의 주택형 칸이 th인 표가 있어서 th·td를 모두 셈 (머리글과 칸 수가 맞아야 함)
      const c = $(tr).children('th,td').map((___, td) => clean($(td).text())).get();
      if (c.length < 3 || c.length !== head.length) return;
      const now = num(c[iNow]);
      if (now && now < 100000) supply += now;
      const area = num(c[iArea]);
      const price = iPrice >= 0 ? num(c[iPrice]) : null;
      const dep = iDep >= 0 ? num(c[iDep]) : null;
      const rent = iRent >= 0 ? num(c[iRent]) : null;
      if (area && area > 10 && area < 300) types.push({ area, now, price, dep, rent });
    });
  });
  if (supply) details.push(`금회 공급: ${supply.toLocaleString('ko-KR')}세대`);
  if (types.length) {
    const as = types.map((t) => t.area);
    details.push(`전용면적: ${Math.min(...as) === Math.max(...as) ? `${Math.min(...as)}㎡` : `${Math.min(...as)}~${Math.max(...as)}㎡`}`);
    const priced = types.filter((t) => t.price);
    for (const t of priced.slice(0, 3)) details.push(`${Math.round(t.area)}㎡ 평균 분양가: ${eok(t.price / 10000)}`);
    const rented = types.filter((t) => t.dep && t.rent);
    for (const t of rented.slice(0, 2)) details.push(`${Math.round(t.area)}㎡ 임대조건: 보증금 ${eok(t.dep / 10000)} · 월 ${Math.round(t.rent / 10000).toLocaleString('ko-KR')}만원`);
  }
  // 공급일정 표: 구분 | 신청일시 | 신청방법
  tableByCaption($, /공급일정/).find('tbody tr').slice(0, 2).each((_, tr) => {
    const c = $(tr).find('td').map((__, td) => clean($(td).text())).get();
    if (c[0] && c[1]) details.push(`${c[0].slice(0, 12)} 접수: ${c[1].replace(/\s*\d{1,2}:\d{2}/g, '').replace(/\s+/g, ' ').trim()}${c[2] ? ` (${c[2]})` : ''}`);
  });
  const win = text.match(/(?:당첨자|순번)\s*발표\s*:?\s*(20\d\d\.\d{1,2}\.\d{1,2})/)?.[1];
  if (win) details.push(`발표: ${short(win)}`);
  const contract = text.match(/계약체결\s*:\s*(20\d\d\.\d{1,2}\.\d{1,2})[^~]{0,6}~\s*(?:20\d\d\.)?(\d{1,2}\.\d{1,2})/);
  if (contract) details.push(`계약: ${short(contract[1])}~${contract[2].replace('.', '/')}`);
  const telRe = (label) => text.match(new RegExp(`${label}\\s*:?\\s*(\\d{2,4}-\\d{3,4}-\\d{4}|\\d{4}-\\d{4})`))?.[1];
  const tel = telRe('문의전화') ?? telRe('문의처');
  if (tel) details.push(`문의: ${tel}`);
  // 공고내용의 첫 안내 문장 두 개 (■로 시작)
  const body = text.match(/공고내용\s*(.+?)\s*공급정보/)?.[1] ?? '';
  const notes = body.split('■').map((x) => clean(x)).filter((x) => x.length > 15).slice(0, 2).map((x) => x.slice(0, 150));
  return { details: details.slice(0, 9), ...(notes.length ? { note: notes.join(' ') } : {}) };
}

/** 글 하나의 상세 페이지를 받아 details를 채움 (크롤러가 새 글에 대해서만 호출) */
export async function enrichApplyhome(post) {
  return parseApplyhomeDetail(await fetchHtml(post.url, { cookies: true, timeoutMs: 20000 }));
}
export async function enrichLh(post) {
  return parseLhDetail(await fetchHtml(post.url, { cookies: true, timeoutMs: 20000 }));
}
