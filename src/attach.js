// 보도자료 첨부파일에서 글자 뽑기. 보도자료 본문에 "자세한 내용은 첨부파일 참고"만 있는 경우가 많아서,
// 첨부된 한글 문서(hwpx)를 받아 글자를 뽑아 두면 앱이 요약할 때 같이 넣는다. (hwpx는 zip 안에 XML이 들어 있는 형식)
import zlib from 'node:zlib';
import * as cheerio from 'cheerio';
import { fetchHtml, fetchBinary } from './fetcher.js';

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const MAX_BYTES = 15 * 1024 * 1024;
export const MAX_ATTACH_CHARS = 6000;
const clean = (t) => String(t ?? '').replace(/[ \t ]+/g, ' ').trim();

/** zip 풀기 (중앙 디렉터리를 읽음). 반환: 파일 이름 → 내용. 암호·zip64 등 못 읽는 항목은 건너뜀 */
export function unzip(buf) {
  const out = new Map();
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 66000); i--) if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) return out;
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  for (let n = 0; n < count && p + 46 <= buf.length; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) break;
    const method = buf.readUInt16LE(p + 10);
    const compSize = buf.readUInt32LE(p + 20);
    const nameLen = buf.readUInt16LE(p + 28); const extraLen = buf.readUInt16LE(p + 30); const commentLen = buf.readUInt16LE(p + 32);
    const local = buf.readUInt32LE(p + 42);
    const name = buf.subarray(p + 46, p + 46 + nameLen).toString('utf8');
    p += 46 + nameLen + extraLen + commentLen;
    if (local + 30 > buf.length || buf.readUInt32LE(local) !== 0x04034b50) continue;
    const start = local + 30 + buf.readUInt16LE(local + 26) + buf.readUInt16LE(local + 28);
    const data = buf.subarray(start, start + compSize);
    try {
      if (method === 0) out.set(name, Buffer.from(data));
      else if (method === 8) out.set(name, zlib.inflateRawSync(data));
    } catch { /* 깨진 항목 */ }
  }
  return out;
}

/** hwpx(한글 문서) → 글자. 문단마다 줄바꿈, 표 칸은 띄어쓰기로 이어짐. hwpx가 아니면 '' */
export function hwpxText(buf) {
  const files = unzip(buf);
  const sections = [...files.keys()].filter((n) => /^Contents\/section\d+\.xml$/i.test(n)).sort((a, b) => parseInt(a.match(/\d+/)[0], 10) - parseInt(b.match(/\d+/)[0], 10));
  const lines = [];
  for (const name of sections) {
    const xml = files.get(name).toString('utf8');
    // 문단(<hp:p>) 안의 글자(<hp:t>)를 이어 붙임. 표 안 문단도 같은 태그라 그대로 잡힘
    for (const para of xml.split(/<hp:p[\s>]/).slice(1)) {
      const text = [...para.matchAll(/<hp:t(?:\s[^>]*)?>([\s\S]*?)<\/hp:t>/g)].map((m) => m[1].replace(/<[^>]+>/g, '')).join('');
      const t = clean(cheerio.load(`<i>${text}</i>`)('i').text());
      if (t) lines.push(t);
    }
  }
  if (lines.length) return dedupe(lines).join('\n');
  // 구역 파일을 못 읽으면 미리보기 글자라도
  const prv = files.get('Preview/PrvText.txt');
  return prv ? dedupe(prv.toString('utf8').split(/\r?\n/).map(clean).filter(Boolean)).join('\n') : '';
}

/** 바로 앞 줄과 같은 줄(머리말·꼬리말 반복)은 뺌 */
function dedupe(lines) {
  return lines.filter((l, i) => i === 0 || l !== lines[i - 1]);
}

/** 요약에 넣을 만큼만 자름 (문단 경계에서) */
export function trimAttach(text, max = MAX_ATTACH_CHARS) {
  if (text.length <= max) return text;
  const cut = text.slice(0, max);
  const nl = cut.lastIndexOf('\n');
  return (nl > max * 0.7 ? cut.slice(0, nl) : cut).trim();
}

/**
 * 보도자료 페이지에서 첨부파일 링크 찾기. 반환: [{ name, url }] (hwpx가 앞에 오게)
 * 링크 글자나 주소에 파일 이름(.hwpx 등)이 있는 것, 내려받기 주소로 보이는 것을 모음
 */
export function findAttachments(html, pageUrl) {
  const $ = cheerio.load(html);
  const out = [];
  const seen = new Set();
  $('a').each((_, a) => {
    const el = $(a);
    const href = (el.attr('href') ?? '').replace(/&amp;/g, '&');
    if (!href || href.startsWith('#') || /^javascript/i.test(href)) return;
    // 파일 이름: 링크 글자, title, 같은 줄(li·td)의 글자 순으로 찾음
    const around = [clean(el.text()), el.attr('title') ?? '', clean(el.closest('li, td, dd, p, div').first().text())];
    const name = around.map((t) => t.match(/[^\s/\\]*[^/\\]*?\.(hwpx|hwp|pdf)\b/i)?.[0]).find(Boolean) ?? href.match(/[^/?=&]+\.(hwpx|hwp|pdf)\b/i)?.[0];
    if (!name) return;
    if (!/down|file|attach|\.(hwpx|hwp|pdf)\b/i.test(href) || /viewer/i.test(href)) return; // '바로보기'(문서 보기 화면)는 파일이 아님
    let url;
    try { url = new URL(href, pageUrl).toString(); } catch { return; }
    if (seen.has(url)) return;
    seen.add(url);
    out.push({ name: clean(name), url, ext: name.match(/\.(hwpx|hwp|pdf)\b/i)[1].toLowerCase() });
  });
  const order = { hwpx: 0, pdf: 1, hwp: 2 };
  return out.sort((a, b) => order[a.ext] - order[b.ext]);
}

/**
 * 글 하나의 첨부 한글 문서(hwpx)를 받아 글자를 뽑음 (크롤러가 새 글에 대해서만 호출).
 * 반환: { attach, attachName } 또는 {} (hwpx 첨부가 없거나 글자를 못 뽑음). hwp·pdf만 있는 글은 지금은 건너뜀
 */
export async function enrichAttach(post) {
  const html = await fetchHtml(post.url, { cookies: true, timeoutMs: 25000 });
  const files = findAttachments(html, post.url).filter((f) => f.ext === 'hwpx');
  for (const f of files.slice(0, 2)) {
    const text = hwpxText(await fetchBinary(f.url, { referer: post.url }));
    if (text.length >= 100) return { attach: trimAttach(text), attachName: f.name.slice(0, 80) };
  }
  return {};
}
