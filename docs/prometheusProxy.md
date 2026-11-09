# Prometheus 읽기 전용 중계 (monitoring)

대시보드 HTML 은 내부망 Prometheus 에 **직접 접근할 수 없어서**, ols 가 대신 조회해서 응답을 그대로 돌려준다.

- 갱신일: 2026-11-09
- 코드: **별도 파일 없이 스냅샷 조회와 같은 3개 파일에 포함**
  (`flightPriceSnapshot/FlightPriceSnapshotController`, `cbc/FlightPriceSnapshotCbc`, `cbc/impl/FlightPriceSnapshotCbcImpl`)
- 기준 HTML: `flightPricingDashboard.html` (519KB, 11/9 버전) 의 Prometheus 호출부를 직접 확인해서 작성

## 1. HTML 이 실제로 하는 호출 (확인 완료)

HTML 코드: `new URL(conn.url + '/api/v1/' + path)`, `conn.url = location.origin + proxyPath` (HTTPS 포털/`*.hanatour.com` 이면 프록시 모드), `method: 'GET'`, `credentials: 'same-origin'`

20초(최소)마다 아래 5건을 동시에 호출한다.

| # | 경로 | 파라미터 |
|---|---|---|
| 1 | `/api/v1/query` | `query`(selector), `time`(epoch 초 정수) |
| 2 | `/api/v1/query_range` | `query`(selector or up), `start`, `end`(epoch 초 정수), `step`(60초 배수, 단위 없는 숫자) |
| 3 | `/api/v1/query` | `query`(freshness, **2.5~3.2KB**), `time` |
| 4 | `/api/v1/rules` | `type=alert` |
| 5 | `/api/v1/query` | `query=up{job="air-api"}`, `time` |

- 조회 범위 선택: 15분 ~ **7일**(604800초). step 은 `max(60, ceil(범위/239/60)*60)` → 시계열당 약 240포인트
- 응답 처리: `status=success`, `data.result`(vector/matrix) 또는 `data.groups`(rules) 형식 검사, **`warnings` 가 있으면 오류 처리** (ols 는 응답을 가공하지 않고 그대로 전달)
- 조회할 지표: `air:stage_*` / `air:naver:stage_*` 레코딩 규칙 16종 + `up{job="air-api"}`

## 2. 구조

```
HTML (prometheus.proxyPath = '/air/monitoring/prometheus')
 └─ GET /air/monitoring/prometheus/api/v1/query        ?query=&time=
 └─ GET /air/monitoring/prometheus/api/v1/query_range  ?query=&start=&end=&step=
 └─ GET /air/monitoring/prometheus/api/v1/rules        ?type=alert
      FlightPriceSnapshotController   (메서드마다 전체 경로 지정 → HTML proxyPath 변경 불필요)
        └─ FlightPriceSnapshotCbc(+Impl)   검증 → WebClient GET → 응답 JSON 그대로 반환
             └─ Prometheus  {monitoring.prometheus.base-url}/api/v1/...
```

## 3. 설정 (선택 사항 — yml 추가 불필요)

Prometheus 주소 기본값은 코드에 있다 (`FlightPriceSnapshotCbcImpl.DEFAULT_PROMETHEUS_BASE_URL = http://10.100.23.95:9090`).
**yml 에 아무것도 추가하지 않아도 동작한다.**

다른 주소가 필요해지면 ols 설정에 아래를 추가하면 **설정 값이 기본값보다 우선**한다. (코드 수정/재빌드 없이 환경별 변경 가능)

```yaml
monitoring:
  prometheus:
    base-url: http://<다른 주소>:9090
```

- HTML 의 `environment: 'prod'` 가 PromQL 라벨 필터이므로 Prometheus 한 곳에 여러 환경 지표가 있다고 보고 한 주소를 공용으로 둠. 환경마다 Prometheus 가 다르면 위 설정으로 환경별 지정
- ols 서버에서 해당 주소로 **네트워크가 열려 있어야 함** (방화벽). DEV/QA/LIVE 각각 확인
- 주소가 소스 코드에 들어가는 것이 단점 (내부 IP). 주소 변경 시 코드 수정이나 설정 추가가 필요

## 4. 보안 설계

| 항목 | 내용 |
|---|---|
| 주소 고정 | Prometheus 주소는 서버(코드 기본값/설정)에서만 정함. UI 가 주소/경로를 정할 수 없음 (SSRF 방지) |
| 경로 allowlist | `query`, `query_range`, `rules` 3개만 매핑. `/api/v1/admin/*`, `/-/reload`, `/api/v1/targets` 등은 중계하지 않음 |
| GET 만 | 읽기 전용 |
| rules 필터 | `type` 은 `alert`/`record` 만 허용. `rule_name[]`, `file[]`, `match[]` 등은 전달하지 않음 (단위 확인: 주입 시도 거부) |
| 값 인코딩 | 쿼리 값은 URI 변수로 바인딩 → `& # +` 가 파라미터를 주입하지 못함. HTML 의 실제 PromQL 13종이 인코딩 후 원문 그대로 복원됨을 확인 |
| 과부하 방지 | PromQL 6000자, 범위 7일, step 최소 1초, 시계열당 5000포인트, 타임아웃 10초 |
| 오류 노출 | Prometheus 오류 본문/내부 주소는 클라이언트에 내려주지 않고 로그에만 기록 (400: 입력 오류, 502: Prometheus 실패) |

## 5. 검증 결과

- Java 8(`--release 8`) + Spring 5.1 + Jackson + Lombok 으로 패키지 전체 컴파일 (사내 클래스는 스텁)
- **가짜 Prometheus**(`ExchangeFunction`)로 77건 확인: 4개 경로 매핑, HTML 이 만드는 PromQL 13종과 범위 4종(15분/2시간/1일/7일) 통과, URL 인코딩 왕복 일치, 응답 그대로 전달, Prometheus 400/503 → 502 변환, 주입 시도 차단
- ❗ **실제 Prometheus 연결, 포털/게이트웨이 경유, HTML 화면 연동은 미검증**

## 6. 확인 필요 / 한계

1. **Prometheus 주소가 둘로 보임**: 사용자가 준 설정은 `10.100.23.95:9090`(코드 기본값으로 사용), HTML 기본값(`DEFAULTS.baseUrl`)과 "연결 정보" 화면 문구는 `203.245.33.80:9090`. **코드 기본값이 운영 Prometheus 가 맞는지 확인**. 프록시 모드에서는 브라우저가 이 주소를 호출하지 않지만 HTML 에 내부/외부 IP 가 노출되어 있으므로, 중계가 동작하면 HTML 설정에서 `baseUrl` 제거를 권장
2. **긴 URL**: freshness 쿼리가 GET 쿼리스트링으로 **약 4.4~4.7KB**. 웹서버/WAF/게이트웨이의 요청줄 제한(보통 8KB, 일부 4KB)에 걸리면 포털 구간에서 400/414 가 날 수 있음. ols 에 도달하기 전 구간이라 코드로 해결 불가 → DEV 에서 실제 호출 확인 필요. 필터(사이트코드 등) 값이 길어지면 더 늘어남
3. **PromQL allowlist 없음 (중요)**: 지금은 어떤 PromQL 이든 길이/범위 제한 안에서 전달됨. 로그인한 사용자가 모든 애플리케이션/호스트 지표를 조회할 수 있음. HTML 이 쓰는 지표는 `air:stage_*`, `air:naver:stage_*`, `up{job="air-api"}` 로 한정되어 있으므로 **메트릭 이름 allowlist 로 좁히는 것이 가능**함 (필요하면 추가 구현)
4. **인증/인가 (운영 반영 전 필수)**: 인프라 지표 노출. 포털 SSO 적용 범위와 권한 검증 필요. Controller 주석에 TODO
5. **부하**: 탭 하나당 20초마다 5건. 여러 사용자가 대시보드를 열어 두면 ols → Prometheus 요청이 사용자 수 × 5건/20초. 캐시(예: 5~10초)를 두면 줄일 수 있음 (현재 미구현)
6. **응답 크기**: `WebClient` 기본 메모리 버퍼 제한(보통 256KB)을 넘는 응답은 502. 7일 범위 + 시계열 많음이면 발생 가능 → 공통 `WebClient` 설정 확인
7. **제한값**: 범위 7일, 5000포인트는 HTML 의 최대값(7일, 약 240포인트)에 맞춘 값. PromQL 6000자는 현재 최대(3214자)의 약 2배
8. **프록시**: ols 서버가 내부 IP 로 나갈 때 사내 HTTP 프록시를 타는 환경이면 해당 대역 예외 설정 필요
