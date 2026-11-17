import iconv from 'iconv-lite';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';

function decode(buf, ctype) {
  // EUC-KR 사이트 대응: 헤더 또는 meta charset / xml encoding 확인
  let charset = (ctype || '').match(/charset=([\w-]+)/i)?.[1];
  if (!charset) {
    const head = buf.subarray(0, 2048).toString('latin1');
    charset = head.match(/(?:charset|encoding)=["']?([\w-]+)/i)?.[1];
  }
  charset = (charset || 'utf-8').toLowerCase();
  return iconv.encodingExists(charset) ? iconv.decode(buf, charset) : buf.toString('utf8');
}

/**
 * cookies: true면 넘김(redirect)을 직접 따라가며 받은 쿠키를 다시 보냄.
 * (국토부 사이트는 첫 요청에 쿠키를 주고 같은 주소로 다시 넘겨서, 쿠키 없이 따라가면 끝없이 돎)
 */
export async function fetchHtml(url, { timeoutMs = 15000, userAgent = UA, cookies = false } = {}) {
  const headers = {
    'User-Agent': userAgent,
    'Accept-Language': 'ko-KR,ko;q=0.9',
    Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
  };
  if (!cookies) {
    const res = await fetch(url, { headers, signal: AbortSignal.timeout(timeoutMs), redirect: 'follow' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return decode(Buffer.from(await res.arrayBuffer()), res.headers.get('content-type'));
  }
  const jar = new Map();
  let cur = url;
  for (let i = 0; i < 6; i++) {
    const res = await fetch(cur, {
      headers: { ...headers, Cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') },
      signal: AbortSignal.timeout(timeoutMs), redirect: 'manual',
    });
    for (const c of res.headers.getSetCookie?.() ?? []) {
      const kv = c.split(';')[0];
      const j = kv.indexOf('=');
      if (j > 0) jar.set(kv.slice(0, j).trim(), kv.slice(j + 1));
    }
    const loc = res.headers.get('location');
    if (res.status >= 300 && res.status < 400 && loc) { cur = new URL(loc, cur).toString(); continue; }
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return decode(Buffer.from(await res.arrayBuffer()), res.headers.get('content-type'));
  }
  throw new Error('넘김이 끝나지 않음');
}

export async function fetchJson(url, { timeoutMs = 45000, userAgent = UA } = {}) {
  const res = await fetch(url, { headers: { 'User-Agent': userAgent, Accept: 'application/json' }, signal: AbortSignal.timeout(timeoutMs) });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

/**
 * 파일 받기 (첨부 문서). 넘김을 직접 따라가며 쿠키를 다시 보냄 → 국토부처럼 쿠키 없이는 끝없이 도는 사이트도 받을 수 있음.
 * 한글·괄호가 섞인 주소는 그대로 보내면 실패하는 서버가 있어서 주소를 한 번 정리함. 실패하면 한 번 더 시도.
 */
export async function fetchBinary(url, { timeoutMs = 30000, referer, maxBytes = 15 * 1024 * 1024, tries = 2 } = {}) {
  let lastError;
  for (let attempt = 0; attempt < tries; attempt++) {
    try {
      const jar = new Map();
      let cur = new URL(url).toString();
      for (let i = 0; i < 6; i++) {
        const res = await fetch(cur, {
          headers: { 'User-Agent': UA, 'Accept-Language': 'ko-KR,ko;q=0.9', Accept: '*/*', ...(referer ? { Referer: referer } : {}), Cookie: [...jar].map(([k, v]) => `${k}=${v}`).join('; ') },
          signal: AbortSignal.timeout(timeoutMs), redirect: 'manual',
        });
        for (const c of res.headers.getSetCookie?.() ?? []) {
          const kv = c.split(';')[0];
          const j = kv.indexOf('=');
          if (j > 0) jar.set(kv.slice(0, j).trim(), kv.slice(j + 1));
        }
        const loc = res.headers.get('location');
        if (res.status >= 300 && res.status < 400 && loc) { cur = new URL(loc, cur).toString(); continue; }
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        if (Number(res.headers.get('content-length') ?? 0) > maxBytes) throw new Error('파일이 너무 큼');
        const buf = Buffer.from(await res.arrayBuffer());
        if (buf.length > maxBytes) throw new Error('파일이 너무 큼');
        return buf;
      }
      throw new Error('넘김이 끝나지 않음');
    } catch (e) {
      lastError = e;
      await new Promise((r) => setTimeout(r, 1500));
    }
  }
  throw lastError;
}
