// 분양가 자료 모으기: 청약홈 APT 분양정보의 지난 공고(목록 → 상세)에서 단지별·주택형별 분양가를 뽑아 out/presale.json에 쌓는다.
// 앱의 '분양가 vs 실거래'가 이 파일을 읽는다. 한 번에 다 받으면 사이트에 부담이라, 실행할 때마다 조금씩(달 단위로 앞에서부터) 채운다.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import * as cheerio from 'cheerio';
import { fetchHtml } from './fetcher.js';

const BASE = 'https://www.applyhome.co.kr/ai/aia/';
const START_YM = '202001'; // 청약홈 운영 시작 무렵
const clean = (t) => String(t ?? '').replace(/\s+/g, ' ').trim();
const num = (t) => { const v = parseFloat(String(t ?? '').replace(/,/g, '')); return Number.isFinite(v) ? v : null; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** "서울특별시" → "서울", "충청북도" → "충북", "전북특별자치도" → "전북" */
export function shortSido(name) {
  const n = String(name ?? '');
  if (/^(충청|전라|경상)/.test(n)) return n[0] + (n.includes('남') ? '남' : '북');
  return n.slice(0, 2);
}

let regionsCache = null;
function regions() {
  if (!regionsCache) {
    const file = path.join(path.dirname(fileURLToPath(import.meta.url)), '../android/app/src/main/assets/regions.json');
    regionsCache = JSON.parse(fs.readFileSync(file, 'utf8'));
  }
  return regionsCache;
}

/**
 * 공급위치 주소 → 시군구 코드들. 구가 있는 시인데 주소에 구가 없으면 그 시의 모든 구 코드.
 *  "경기도 성남시 분당구 정자동 1" → ['41135'], "경기도 부천시 괴안동 2" → 부천시의 구 3곳
 */
export function lawdCodes(addr, list = regions()) {
  const a = clean(addr);
  const sido = list.find((r) => shortSido(r.sido) === shortSido(a.split(' ')[0]));
  if (!sido) return [];
  const rest = a.split(' ').slice(1).join(' ');
  if (sido.sigungu.length === 1) return [sido.sigungu[0].code]; // 세종
  const exact = sido.sigungu.filter((s) => rest.startsWith(s.name + ' ') || rest === s.name).sort((x, y) => y.name.length - x.name.length);
  if (exact.length) return [exact[0].code];
  const city = rest.split(' ')[0];
  return sido.sigungu.filter((s) => s.name.split(' ')[0] === city).map((s) => s.code);
}

/** 목록 한 쪽 → 공고들 { id, hm, name, sido, date } */
export function parsePresaleList(html) {
  const $ = cheerio.load(html);
  const out = [];
  $('tbody tr[data-pbno]').each((_, el) => {
    const tr = $(el);
    const cells = tr.find('td').map((__, td) => clean($(td).text())).get();
    const id = tr.attr('data-pbno');
    const name = clean(tr.attr('data-honm') || tr.find('td.txt_l').first().text());
    const date = cells.find((c) => /^20\d{2}-\d{2}-\d{2}$/.test(c)) ?? '';
    if (id && name) out.push({ id, hm: tr.attr('data-hmno') || id, name, sido: cells[0] ?? '', date });
  });
  return out;
}

/**
 * 상세 → { addr, total, movein, prices: { "84": [최저, 최고] } } (만원. 전용면적 정수 부분별로 묶음)
 */
export function parsePresaleDetail(html) {
  const $ = cheerio.load(html);
  $('script,style,noscript').remove();
  const text = clean($('body').text());
  const pair = (label) => {
    let v = '';
    $('tr').each((_, tr) => {
      const c = $(tr).children('th,td').map((__, x) => clean($(x).text())).get();
      if (!v && c[0] === label && c[1]) v = c[1];
    });
    return v;
  };
  const prices = {};
  $('table').filter((_, t) => /공급금액/.test($(t).find('caption').text())).first().find('tr').each((_, tr) => {
    const c = $(tr).find('td').map((__, td) => clean($(td).text())).get();
    const area = parseInt(c[0], 10);
    const price = num(c[1]);
    if (!Number.isFinite(area) || price == null || price < 1000) return;
    const cur = prices[area] ?? [price, price];
    prices[area] = [Math.min(cur[0], price), Math.max(cur[1], price)];
  });
  const total = parseInt(String(pair('공급규모')).replace(/,/g, ''), 10);
  const movein = text.match(/입주예정월\s*:\s*(\d{4})\.(\d{1,2})/);
  return {
    addr: pair('공급위치'),
    ...(Number.isFinite(total) ? { total } : {}),
    ...(movein ? { movein: `${movein[1]}-${movein[2].padStart(2, '0')}` } : {}),
    prices,
  };
}

const nextYm = (ym) => { const y = +ym.slice(0, 4); const m = +ym.slice(4); return m === 12 ? `${y + 1}01` : `${y}${String(m + 1).padStart(2, '0')}`; };
const kstYm = (now) => { const d = new Date(now + 9 * 3600_000); return `${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}`; };

async function listMonth(ym) {
  const all = [];
  for (let page = 1; page <= 40; page++) {
    const html = await fetchHtml(`${BASE}selectAPTLttotPblancListView.do?beginPd=${ym}&endPd=${ym}&pageIndex=${page}`, { cookies: true, timeoutMs: 20000 });
    const rows = parsePresaleList(html);
    const fresh = rows.filter((r) => !all.some((x) => x.id === r.id));
    all.push(...fresh);
    if (rows.length < 10 || fresh.length === 0) break;
    await sleep(200);
  }
  return all;
}

/**
 * prev: 이전 presale.json. 달 단위로 cursor부터 이번 달까지 훑으며 아직 없는 공고의 상세를 받음.
 * 한 번 실행에 상세 maxDetails건, budgetMs 안에서만. 다 따라잡은 뒤에는 최근 두 달만 다시 봄(6시간마다).
 */
export async function collectPresale(prev, { now = Date.now(), maxDetails = 150, budgetMs = 150_000, fetchList = listMonth, fetchDetail } = {}) {
  const started = Date.now();
  const cur = kstYm(now);
  const state = { version: 1, updatedAt: prev?.updatedAt ?? 0, cursor: prev?.cursor ?? START_YM, items: prev?.items ?? [], empty: prev?.empty ?? [] };
  const caughtUp = state.cursor >= cur;
  if (caughtUp && now - state.updatedAt < 6 * 3600_000) return prev;
  const have = new Set([...state.items.map((i) => i.id), ...state.empty]);
  const detail = fetchDetail ?? (async (r) => parsePresaleDetail(await fetchHtml(`${BASE}selectAPTLttotPblancDetail.do?houseManageNo=${r.hm}&pblancNo=${r.id}`, { cookies: true, timeoutMs: 20000 })));
  let done = 0; let failed = 0;
  // 따라잡은 뒤에는 지난달부터 다시 훑음 (새 공고가 계속 추가되므로)
  let ym = caughtUp ? (cur.endsWith('01') ? `${+cur.slice(0, 4) - 1}12` : String(+cur - 1)) : state.cursor;
  let complete = true;
  while (ym <= cur) {
    let rows;
    try { rows = await fetchList(ym); } catch (e) { console.log(`[presale] ${ym} 목록 실패: ${e.message}`); complete = false; break; }
    let monthDone = true;
    for (const r of rows) {
      if (have.has(r.id)) continue;
      if (done + failed >= maxDetails || Date.now() - started > budgetMs) { monthDone = false; break; }
      try {
        const d = await detail(r);
        if (Object.keys(d.prices).length === 0) { state.empty.push(r.id); } // 분양가 표가 없는 공고는 다시 보지 않음
        else state.items.push({ id: r.id, name: r.name, sido: r.sido, date: r.date, lawd: lawdCodes(d.addr), ...d });
        have.add(r.id); done++;
      } catch (e) {
        failed++;
        if (failed <= 2) console.log(`[presale] ${r.name}(${r.id}) 상세 실패: ${e.message}`);
      }
      await sleep(250);
    }
    if (!monthDone || failed > 0) { complete = false; break; }
    if (!caughtUp && ym < cur) state.cursor = nextYm(ym);
    ym = nextYm(ym);
  }
  if (!caughtUp && complete) state.cursor = cur;
  state.updatedAt = now;
  state.items.sort((a, b) => (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
  console.log(`[presale] 상세 ${done}건 추가(실패 ${failed}), 모두 ${state.items.length}건, 다음 시작 ${state.cursor}${state.cursor >= cur ? ' (따라잡음)' : ''}. 예: ${JSON.stringify(state.items.find((i) => i.lawd?.length) ?? state.items[0] ?? null)?.slice(0, 300)}`);
  return state;
}

export async function runPresale(outDir) {
  const file = path.join(outDir, 'presale.json');
  let prev = null;
  try { prev = JSON.parse(fs.readFileSync(file, 'utf8')); } catch { /* 처음 */ }
  try {
    const next = await collectPresale(prev);
    if (next) fs.writeFileSync(file, JSON.stringify(next));
  } catch (e) {
    console.log('[presale] 수집 실패:', e.message);
  }
}
