# Prometheus 읽기 전용 중계 (monitoring)

대시보드 HTML 은 내부망 Prometheus 에 **직접 접근할 수 없어서**, ols 가 대신 조회해서 응답을 그대로 돌려준다.
**`flightPricingDashboard.html` 이 보내는 형태만 허용**하는 짧은 구현이다. (임의 PromQL 실행 불가)

- 갱신일: 2026-11-09
- 코드: 스냅샷 조회와 같은 3개 파일 (`flightPriceSnapshot/FlightPriceSnapshotController`, `cbc/FlightPriceSnapshotCbc`, `cbc/impl/FlightPriceSnapshotCbcImpl`)
- Java 8, 외부 라이브러리 추가 없음 (`HttpURLConnection` 사용)

## 1. HTML 이 보내는 호출 (확인 완료)

HTML: `proxyPath + '/api/v1/' + path`, `GET`, `credentials: same-origin`. 탭 하나당 20초마다 5건을 동시에 호출한다.

| 경로 | 파라미터 | 서버 허용 조건 |
|---|---|---|
| `/api/v1/query` | `query`, `time`(epoch 초 정수) | query 는 아래 4종 템플릿만 |
| `/api/v1/query_range` | `query`, `start`, `end`, `step`(60초 배수 정수) | query 는 템플릿 2종만, 범위 ≤ 24시간, ≤ 250포인트 |
| `/api/v1/rules` | `type=alert` | `alert` 만 (생략 가능) |

허용 query 템플릿 (HTML 의 `selector()` / `freshnessQuery()` 와 동일 규칙):

1. `{__name__=~"<16종 이름>",environment="..",application="..",site_code=~"..|"}` (라벨은 있으면 포함)
2. 1번 + ` or up{job="air-api"}`
3. freshness: `(label_replace(time()-timestamp(...),"dashboard_metric","<이름>","","")) or ...` 16개 + up 1개 (**query 만**, range 불가)
4. `up{job="air-api"}`

## 2. 검증 방식 (임의 PromQL 차단)

쿼리에서 라벨 값(`environment`, `application`, `site_code`)만 뽑아 허용 문자(`[A-Za-z0-9_.:-]{1,64}`)인지 검사한 뒤,
**HTML 과 같은 규칙으로 쿼리를 서버가 다시 만들어서 요청 쿼리와 한 글자라도 다르면 거부**한다.
(파서 없이 문자열 비교라 코드가 짧고, 허용 형태 밖의 입력은 구조상 통과할 수 없음)

| 제한 | 값 | 근거 |
|---|---|---|
| 조회 범위 | 24시간 | HTML 선택지 1h/2h/6h/24h |
| 포인트 | 250 | HTML step 계산식 → 최대 약 206포인트 |
| `time/start/end` | epoch 정수, 서버 시각 +60초 이내 | HTML 은 `Math.floor(now/1000)` 사용 |
| Prometheus 쿼리 실행 제한 | `timeout=6000ms` 를 서버가 강제 | 브라우저가 늘릴 수 없음 |
| 연결/읽기 제한 | 2초 / 8초 | HTML 요청 제한 12초보다 짧게 |
| 응답 크기 | 16MB | 초과 시 중단 |
| 리다이렉트 | 따르지 않음 | |
| 인바운드 쿠키/인증 헤더 | 전달하지 않음 | |

오류는 입력 400(상세 사유 미노출), Prometheus 실패/설정 누락 502, 시간 초과 504 로 응답하고 Prometheus 원문 오류는 로그에만 남긴다.

## 3. 설정 (선택 사항 — yml 추가 불필요)

Prometheus 주소 기본값은 코드에 있다 (`DEFAULT_PROMETHEUS_BASE_URL = http://10.100.23.95:9090`).
다른 주소가 필요하면 ols 설정에 추가하면 **설정 값이 우선**한다.

```yaml
monitoring:
  prometheus:
    base-url: http://<다른 주소>:9090
```

## 4. 검증 결과

Java 8(`--release 8`) 로 컴파일하고, **실제 HTTP 서버(가짜 Prometheus)** 를 띄워 **2,849건** 확인했다.

- **HTML 자신의 코드**(`selector`/`freshnessQuery`/`catalog` 를 HTML 에서 그대로 꺼내 실행)로 400가지 필터 조합(scope 2 × environment 4 × application 5 × site_code 5 × 규칙 2종) × 쿼리 4종 → 모두 통과, 서버에 원문 그대로 도착, `timeout=6000ms` 추가 확인
- freshness 를 range 로 보내면 거부, 1h/2h/6h/24h 범위 통과, 25시간/1441포인트/step 0/단위 있는 step 거부
- 임의 쿼리(`up`, `{__name__=~".+"}`, `sum(rate(...))`, 허용 쿼리 뒤 이어붙이기, 이름 일부만, 라벨 값 변조/주입) 거부
- `rules`: `alert` 만 허용, 주입 시도 거부
- 업스트림 400/503 → 502, 리다이렉트 안 따라감, 17MB 응답 → 502, 응답 지연 → 504(8.0초), 접속 불가/설정 없음 → 502
- ❗ **실제 Prometheus 연결, 포털/게이트웨이 경유, HTML 화면 연동은 미검증**

## 5. 확인 필요 / 한계

1. **ols 서버에서 Prometheus 로 접속이 되는지 (가장 중요)**: 방화벽 확인. 접속 가능한 서버가 항공API 쪽뿐이면 ols 직접 호출은 동작하지 않으므로, 항공API 에 중계를 두고 ols 는 전달만 하는 구조로 바꿔야 함
2. **인증/인가 (운영 반영 전 필수)**: 코드에 권한 검사 없음. 포털 SSO 적용 범위 확인. Controller 주석에 TODO
3. **브라우저 시계 오차**: 요청 시각이 서버보다 60초 넘게 앞서면 400. PC 시계가 크게 어긋나면 화면 전체가 실패
4. **동시 호출/캐시 없음**: 탭 하나가 20초마다 5건. 사용자가 많으면 ols → Prometheus 요청이 `사용자 수 × 5건/20초`. 필요하면 동시 한도나 5~10초 캐시 추가
5. **HTML 이 바뀌면 같이 수정**: 허용 템플릿이 HTML 의 `selector()/freshnessQuery()` 와 메트릭 이름 16종·prefix 2종에 고정되어 있음. 레코딩 규칙이나 쿼리 형태가 바뀌면 `METRIC_SUFFIXES`/템플릿 수정 필요 (바뀌면 400 으로 즉시 드러남)
6. **Prometheus 주소가 둘로 보임**: 코드 기본값 `10.100.23.95:9090`, HTML 기본값/연결 정보 화면 문구는 `203.245.33.80:9090`. 운영 Prometheus 가 맞는지 확인
7. **긴 URL**: freshness 쿼리가 GET 쿼리스트링으로 약 4.4~4.7KB. 포털/게이트웨이 요청줄 제한(보통 8KB, 일부 4KB)에 걸리면 ols 도달 전에 400/414

## 6. 별도로 받은 참고 구현(Java 17, Spring Security)과의 관계

`PrometheusProxy*` 참고 구현(항공API 용으로 보이는 Java 17/Spring Boot)은 이 저장소에 포함하지 않았다.
같은 허용 템플릿(메트릭 32종, 라벨, freshness 구조)을 이 코드가 더 짧게 구현했고, 둘이 동시에 같은 경로(`/air/monitoring/prometheus`)를 서비스하면 안 된다.
참고 구현에는 있고 이 코드에는 **없는 것**: Spring Security 권한(`MONITORING_READ`), 동시 한도(429), JSON 응답 형식 검사, 응답 스트리밍 크기 차단(이 코드는 읽으며 차단).
