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
