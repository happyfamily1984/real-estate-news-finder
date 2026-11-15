import iconv from 'iconv-lite';

const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';

export async function fetchHtml(url, { timeoutMs = 15000, userAgent = UA } = {}) {
  const res = await fetch(url, {
    headers: {
      'User-Agent': userAgent,
      'Accept-Language': 'ko-KR,ko;q=0.9',
      Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    },
    signal: AbortSignal.timeout(timeoutMs),
    redirect: 'follow',
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());

  // EUC-KR 사이트 대응: 헤더 또는 meta charset 확인
  const ctype = res.headers.get('content-type') || '';
  let charset = ctype.match(/charset=([\w-]+)/i)?.[1];
  if (!charset) {
    const head = buf.subarray(0, 2048).toString('latin1');
    charset = head.match(/charset=["']?([\w-]+)/i)?.[1];
  }
  charset = (charset || 'utf-8').toLowerCase();
  return iconv.encodingExists(charset) ? iconv.decode(buf, charset) : buf.toString('utf8');
}
