# 부동산뉴스파인더 — 작업 인계 문서

새 창에서 이어서 작업할 때 이 문서부터 읽으면 됩니다. (2026-11-16 기준)

## 1. 무엇을 만드는가

부동산 뉴스·정부/공공 정보·커뮤니티 글을 모아, 스레드(Threads)에 올릴 요약 글과 이미지를 만들어 주는 안드로이드 앱.

- 앱: Kotlin + Compose, 사이드로드 APK (`android/`)
- 수집기: GitHub Actions에서 도는 Node 22 스크립트 (`src/`)
- 저장소: `happyfamily1984/real-estate-news-finder`
  - 개발 브랜치(기본 브랜치): `ccr-63d315cd-vo3531`
  - `data` 브랜치: `posts.json`, `state.json`, `stats.json` (커밋 1개로 덮어씀)
  - `screens` 브랜치: CI가 찍은 화면 스크린샷

## 2. 작업 규칙 (사용자 지시)

- 답변은 짧게, 결과 중심. 화면 글자는 전부 한국어.
- 유료 API 금지. 실행 중 Claude 토큰 사용 금지. AI는 사용자의 무료 키만: Gemini, Groq, OpenRouter. (Cerebras는 유료라 제외)
- **출시는 시킬 때만.** 평소엔 `[skip ci]` 커밋으로 저장하고, 로컬에서 만든 테스트 APK를 채팅으로 보냄. 버전은 매번 올림.
- 막히면 GitHub 공개 코드를 검색해서 방법을 찾음.
- 사용자가 스크린샷을 보내면 그걸 보고 고침.
- 네이버 비밀번호는 앱이 다루지 않음. 앱 안 웹뷰에서 네이버 화면에 직접 로그인. 채팅으로 계정 정보를 받지 않음.
- PR은 요청할 때만.

## 3. 현재 상태

- 출시된 버전: **v1.0.5** (versionCode 205)
- 마지막으로 보낸 테스트 APK: **1.2.1-test** (versionCode 459413). 다음은 1.2.2-test.
- 마지막 커밋: `b462bc2` 무순위 공고 상세 (서버 쪽 수정이라 APK 재설치 불필요)
- 수집: 21개 소스 모두 정상 (동아일보·구글뉴스는 가끔 실패). 통계 10종.
- GitHub 시크릿: `REB_API_KEY` 등록됨. `KOSIS_API_KEY`는 아직 없음.

### 기기에서 아직 확인 안 된 것

- 커뮤니티 글 캡처와 작성자 모자이크 위치
- 네이버 로그인 흐름 (부동산스터디)
- 글 편집 시 키보드가 입력칸을 가리지 않는지
- 뉴스 기사 머리 부분 캡처 잘림 여부
- 스레드 본문 붙여넣기 방식 입력

### 남은 일 / 제안만 해둔 것

- KOSIS 통계 연결 (사용자가 `KOSIS_API_KEY` 시크릿을 넣어야 함)
- SH·GH 공고 소스 추가
- 예약 실행(cron)이 거의 안 돎 → 지금은 수동 실행으로 데이터 갱신
- LH 상세 정보는 한 번에 12건씩만 채워짐
- v1.0.5 이후 작업은 출시 요청 없음

## 4. 수집기 (`src/`)

테스트: `npm test` (20개 통과)

| 파일 | 역할 |
|---|---|
| `sources.js` | 소스 목록과 옵션 |
| `fetcher.js` | `fetchHtml` (cookies: true면 쿠키 들고 리다이렉트 따라감 — 국토부·LH·청약홈에 필요), `fetchJson` |
| `feeds.js` | RSS 해석, 구글뉴스 (1.5초 간격 직렬 요청, 429/503 재시도) |
| `korea.js` | 정책브리핑 목록 페이지 해석 (RSS는 2026-07-01 종료) |
| `navercafe.js` | 부동산스터디 주간 인기글 API |
| `apply.js` | 청약홈·LH 목록과 상세 해석 |
| `topics.js` | 주제 태그, 관련성 판별, 독자 관심 점수 `threadPick` |
| `score.js` | 점수 계산, 비슷한 기사 묶기(`dupOf`) |
| `crawler.js` | 이전 상태와 합치기, 상세 보강, 보관 기간 |
| `stats.js` | KB(키 없이) + R-ONE(`REB_API_KEY`) 통계, 3시간마다 갱신 |
| `diag.js` | 진단용. 조사할 때마다 다시 씀 |

### 소스

- **뉴스**: gnews(구글뉴스 검색 9개), mk, yna, donga, khan, hani, asiae, sedaily. (hankyung은 403이라 꺼둠)
- **정부·공공**: applyhome(청약홈 APT), applyhome-remain(무순위), lh-sale(LH 분양), lh(LH 임대), molit(국토부), seoul, gg, korea-press, korea-policy, gov-search
- **커뮤니티**: dcinside(부동산갤 개념글), ppomppu, navercafe(부동산스터디, cafeId 12730407)

### 청약홈 주소 (주의)

- 일반 분양 상세: `selectAPTLttotPblancDetail.do`
- 무순위 상세: `selectAPTRemndrLttotPblancDetailView.do` (끝에 `View`가 붙음. 없으면 404)
- 상세 해석 방식을 바꾸면 `sources.js`의 `detailV`를 올려야 기존 글이 다시 채워짐

### 피드 형식

```
{version, updatedAt, topics, sources:[{id,name,board,url,color,kind,boostHours?,status}],
 posts:[{id,source,title,url,views,comments,likes,score,pick,viewsPerMin,firstSeenAt,rank,
         publishedAt?,tags?,related?,outlet?,summary?,dupOf?,details?,note?}]}
```
kind: `news` | `gov` | `community`

## 5. 앱 (`android/app/src/main/java/com/realestatenewsfinder/app/`)

- 패키지 `com.realestatenewsfinder.app`, minSdk 26, targetSdk 35
- AGP 8.10.1, Kotlin 2.1.21, Compose BOM 2025.05.00
- 서명 키: `android/app/app.keystore` (비밀번호·별칭 `communityfinder`)

| 파일 | 역할 |
|---|---|
| `MainActivity.kt` | 탭(뉴스/정부·공공/커뮤니티/통계/설정), 머리말, 계정 전환 버튼(우상단), 주제 칩(커뮤니티 탭은 커뮤니티 종류), 기본 정렬 최신, 뒤로가기 단계와 종료 확인 |
| `Feed.kt`, `Prefs.kt`, `PostStore.kt`, `HotPostWorker.kt` | 데이터, 설정, 알림 |
| `PageScanner.kt` | 숨은 웹뷰로 글 캡처. 작성자 영역 좌표, 광고 숨김 |
| `PreviewScreen.kt` | 글 미리보기, 네이버 로그인 안내 |
| `ExportScreen.kt` | 스레드 내보내기. 뉴스=기사 머리 캡처+핵심정리 카드, 커뮤니티=캡처+모자이크+요약, 링크 넣기 스위치 |
| `CardMaker.kt`, `StatCards.kt` | 자체 제작 이미지와 워터마크 |
| `ThreadsKit.kt` | 모자이크, 긴 이미지 자르기, 공유 |
| `Summarizer.kt`, `Gemini.kt`, `Groq.kt`, `ExtraAi.kt` | 요약. 주제=제목, 스레드 말투, 없는 숫자 금지 검사 |
| `Stats.kt`, `StatsScreen.kt` | 통계 탭, 네이버 로그인 화면 |
| `SettingsScreen.kt`, `OnboardingScreen.kt` | 설정, 첫 실행 안내 |
| `ThreadsAutoService.kt` | 접근성으로 스레드 계정 전환 (대상 `buals_zip`), 본문 붙여넣기 |

계정 전환은 1.1.8부터 동작 확인됨. 대기 시간: 첫 화면 12초, 로딩·로그아웃·로그인 45초, 그 외 4초, 전체 120초, 로그인 목록 20초.

## 6. 빌드·확인 방법

이 작업 환경에서는 한국 사이트 접속이 막혀 있어서, 수집 확인은 GitHub Actions로 함.

**수집 확인**
1. 커밋·푸시 (`[skip ci]`)
2. `crawl.yml` 수동 실행 (진단이 필요하면 입력 `diag: true` → `src/diag.js` 실행)
3. 작업 로그 확인
4. 결과는 `git fetch --depth=1 origin data && git show FETCH_HEAD:posts.json` 로 확인 (raw 주소는 몇 분 캐시됨)

**테스트 APK**
```
cd android && export ANDROID_HOME=/opt/android-sdk
VC=$(( ($(date +%s) - 1767225600) / 60 ))
VERSION_CODE=$VC VERSION_NAME=1.2.2-test ./gradlew assembleRelease -q
# 결과: app/build/outputs/apk/release/app-release.apk → 채팅으로 전송
```
- 로컬 테스트: `./gradlew testDebugUnitTest -PlocalJvmOnly` (26개 통과). Robolectric·스크린샷 테스트는 Actions에서만 됨.
- 새 환경이면 Android SDK를 `/opt/android-sdk`에 다시 설치하고 `android/local.properties`에 `sdk.dir` 지정.

**출시**: `android.yml`을 `release: true`로 수동 실행 (사용자가 시킬 때만).
