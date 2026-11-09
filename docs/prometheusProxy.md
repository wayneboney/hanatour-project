# Prometheus 읽기 전용 중계 (monitoring)

대시보드 HTML 은 내부망 Prometheus(`http://10.100.23.95:9090`)에 **직접 접근할 수 없어서**, ols 가 대신 조회해서 응답을 그대로 돌려준다.

- 작성일: 2026-11-09
- 코드: **별도 파일 없이 스냅샷 조회와 같은 3개 파일에 포함** (`flightPriceSnapshot/FlightPriceSnapshotController`, `cbc/FlightPriceSnapshotCbc`, `cbc/impl/FlightPriceSnapshotCbcImpl`)
- 컴파일 검증: Java 8(`--release 8`) + Spring 5.1 + Jackson + Lombok 으로 패키지 전체 컴파일 확인(사내 클래스는 스텁), 경로/검증/인코딩 17건 확인. **실제 Prometheus 연결/HTML 연동은 미검증**

## 1. 구조

```
HTML (prometheus.proxyPath = '/air/monitoring/prometheus')
 └─ GET /air/monitoring/prometheus/api/v1/query        ?query=&time=
 └─ GET /air/monitoring/prometheus/api/v1/query_range  ?query=&start=&end=&step=
      FlightPriceSnapshotController   (메서드마다 전체 경로 지정 → HTML proxyPath 변경 불필요)
        └─ FlightPriceSnapshotCbc(+Impl)   검증 → WebClient GET → 응답 JSON 그대로 반환
             └─ Prometheus  {monitoring.prometheus.base-url}/api/v1/query | query_range
```

## 2. 파일 (스냅샷 조회와 공용)

| 파일 | Prometheus 관련 내용 |
|---|---|
| `FlightPriceSnapshotController.java` | `prometheusQuery`, `prometheusQueryRange` 두 메서드 (`/air/monitoring/prometheus/...`) |
| `cbc/FlightPriceSnapshotCbc.java` | `prometheusQuery`, `prometheusQueryRange` |
| `cbc/impl/FlightPriceSnapshotCbcImpl.java` | 검증, Prometheus 호출. 생성자에 `WebClient` 와 `@Value("${monitoring.prometheus.base-url:}")` 추가 |

> 컨트롤러는 클래스 공통 `@RequestMapping` 이 없고 메서드마다 전체 경로를 지정한다.
> - `/air/flightPrice/snapshots` (스냅샷)
> - `/air/monitoring/prometheus/api/v1/query`, `/air/monitoring/prometheus/api/v1/query_range` (Prometheus)

## 3. 필수 설정 (ols application 설정, 환경별로 다름)

```yaml
monitoring:
  prometheus:
    base-url: http://10.100.23.95:9090
```

- 설정이 없으면 호출 시 502 (`Prometheus 연결 설정이 없습니다`). 애플리케이션 기동은 막지 않음
- DEV/QA/LIVE 가 각각 다른 Prometheus 를 쓰면 환경별로 지정. HTML 의 `environment: 'prod'` 는 PromQL 라벨용이며 이 주소와 무관
- ols 서버에서 `10.100.23.95:9090` 으로 **네트워크가 열려 있어야 함** (방화벽 확인)

## 4. 보안 설계

| 항목 | 내용 |
|---|---|
| 주소 고정 | Prometheus 주소는 서버 설정으로만 지정. UI 가 주소/경로를 정할 수 없음 (SSRF 방지) |
| 경로 allowlist | `/api/v1/query`, `/api/v1/query_range` 두 개만 매핑. `/api/v1/admin/*`, `/-/reload` 등은 중계하지 않음 |
| GET 만 | 읽기 전용 |
| 값 인코딩 | `query` 는 URI 변수로 바인딩 → `& # +` 등이 파라미터를 주입하지 못함 (단위 확인: `&`→`%26`, `#`→`%23`) |
| 과부하 방지 | PromQL 2000자, 범위 7일, step 최소 1초, 시계열당 5000포인트 상한, 호출 타임아웃 10초 |
| 오류 노출 | Prometheus 오류 본문/내부 주소는 클라이언트에 내려주지 않고 로그에만 기록 (400: 입력 오류, 502: Prometheus 실패) |

## 5. 확인 필요 / 한계

1. **호출 형태 가정**: Prometheus 표준 API 경로(`proxyPath + /api/v1/query|query_range`)를 가정함. 실제 HTML 이 다른 형태(`proxyPath?query=...` 등)나 다른 API(`/api/v1/series`, `/api/v1/label/.../values`)를 쓰면 **Controller 수정 필요** → `TRAFFIC_MONITORING.md` 와 HTML 의 Prometheus 호출부 확인 필요
2. **PromQL allowlist 없음 (중요)**: 지금은 어떤 PromQL 이든 전달됨. 로그인한 사용자가 **모든 애플리케이션/호스트 지표를 조회**할 수 있고 무거운 쿼리를 보낼 수 있음. 대시보드가 쓰는 쿼리가 확정되면 **메트릭 이름 allowlist** 로 좁히는 것을 권장
3. **인증/인가 (운영 반영 전 필수)**: 인프라 지표 노출. 포털 SSO 적용 범위와 권한 검증 필요. Controller 주석에 TODO
4. **응답 크기**: `WebClient` 기본 메모리 버퍼 제한(보통 256KB)을 넘는 응답은 502. 범위가 길거나 시계열이 많으면 발생 → 공통 `WebClient` 설정 확인 또는 step 을 키울 것
5. **제한값 조정**: 범위 7일, 5000포인트 등은 임의 기준. UI 의 실제 조회 범위에 맞춰 `PrometheusProxyCbcImpl` 상수 수정
6. **프록시**: ols 서버가 내부 IP 로 나갈 때 사내 HTTP 프록시를 타는 환경이면 `10.*` 대역 예외 설정 필요
