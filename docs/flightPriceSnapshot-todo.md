# flightPriceSnapshot 적용 체크리스트 (고쳐야 할 것 / 확인할 것)

코드는 `claude/busy-knuth-v7bcfs` 브랜치에 있음. 이 문서는 **실제 프로젝트에 붙일 때 사람이 해야 할 일**만 정리한 것.
(구조/API 명세는 `docs/flightPriceSnapshot.md` 참고)

- 작성일: 2026-11-05
- 코드 상태: 컴파일/실행 **미검증** (개발 환경에 사내 라이브러리 `hntframe`, DB 접속 없음)

---

## A. 반드시 해야 함 (안 하면 동작하지 않음)

### A-1. Mapper 어노테이션을 price(MariaDB) 데이터소스용으로 변경 ✅ 반영 완료

| 항목 | 내용 |
|---|---|
| 원인 | 테이블은 **MariaDB** (`jdbc:mariadb://172.31.250.31:6001/AIR01`, `price:` 데이터소스). `@AirMapper` 는 기본(Oracle) 데이터소스용 마커 어노테이션 |
| 조치 | `@AirMapper` → **`@PriceMapper`** (`com.hanatour.air.cmn.common.config.datasource.PriceMapper`) |
| 수정 파일 | `ec/qc/FlightPriceSnapshotMapper.java` (import 와 어노테이션 한 줄) |
| 남은 확인 | `@PriceMapper` 가 붙은 Mapper 의 **XML 위치 설정**(`mapperLocations`)이 우리 XML 경로(`mapper/com/hanatour/air/ols/flightPriceSnapshot/*.xml`)를 포함하는지. 기존 `@PriceMapper` Mapper 의 XML 위치와 비교 |

### A-2. 프로젝트에 파일 복사 (최신본으로)

경로 기준: `src/main/java/com/hanatour/air/ols/flightPriceSnapshot/`

| 파일 | 비고 |
|---|---|
| `FlightPriceSnapshotController.java` | GET 방식으로 **변경됨** |
| `cbc/FlightPriceSnapshotCbc.java` | 메서드명 `getSnapshots` 로 **변경됨** |
| `cbc/impl/FlightPriceSnapshotCbcImpl.java` | 응답 조립 로직 **변경됨** |
| `cbc/vo/FlightPriceSnapshotReqVo.java` | `dateStart`, `dateEnd` 만 남음 |
| `cbc/vo/FlightPriceSnapshotVo.java` | 81개 필드로 **변경됨** |
| `ec/qc/FlightPriceSnapshotMapper.java` | 메서드 1개만 남음 |
| `src/main/resources/mapper/com/hanatour/air/ols/flightPriceSnapshot/FlightPriceSnapshotMapper.xml` | **MariaDB 문법으로 변경됨** |

이전에 복사해 둔 버전이 있다면 **전부 덮어쓰기**. 삭제된 것: `bc/` 폴더 전체, `FlightPriceSnapshotSearchVo`, `FlightPriceSnapshotCompetitorVo`, Oracle DDL.

### A-3. DB 인덱스 추가

```sql
CREATE INDEX idx_snapshot_captured_at ON FLIGHT_PRICE_SNAPSHOT (captured_at);
```
(`sql/FLIGHT_PRICE_SNAPSHOT.index.mariadb.sql`)

- 기존 인덱스 `(reservation_code, captured_at)` 는 예약코드 없는 기간 조회에 못 씀 → 없으면 **풀스캔**
- 대용량 테이블이면 DBA 와 적용 시간 협의

### A-4. 프론트팀에 요청

`flightPricingDashboard.html` 의 `PRICING_DASHBOARD_CONFIG.endpoint` 변경:

```js
endpoint: '/air/flightPriceSnapshot/snapshots'
```

기본값 `/air/dynamicPricing/snapshots` 는 프론트가 제안한 경로일 뿐이라, 우리 Controller 에 연결되지 않음.

---

## B. 확인 필요 (맞는지 모름, 틀리면 수정)

| # | 확인 항목 | 틀릴 때 수정할 곳 |
|---|---|---|
| B-1 | **DB 시각이 KST 로 저장**되어 있는지 (`captured_at`, `created_at`) | UTC 면 XML 의 기간 경계와 `+09:00` 표기 변환 필요 |
| B-2 | **테이블명 대소문자**: `FLIGHT_PRICE_SNAPSHOT` (대문자). Linux MariaDB 는 구분함 | XML 의 `FROM` 절 |
| B-3 | DEV/QA/LIVE 의 DB명이 `AIR01` 과 달라도 되는지 | SQL 에서 DB명을 생략했으므로 JDBC URL 기본 DB 사용 (수정 불필요) |
| B-4 | MyBatis **언더스코어→카멜 자동 매핑**이 켜져 있는지 (`snapshot_id` → `snapshotId`) | 꺼져 있으면 VO 필드가 전부 null |
| B-5 | `is_direct_only` 컬럼이 `tinyint(1)` 인지 | 숫자 1/0 이 아니면 XML 수정 |
| B-6 | **하루 데이터 건수** | 아래 C-1 참고 |

---

## C. 첫 호출 때 확인

### 호출 예시

```bash
curl -G "http://127.0.0.1:8081/air/flightPriceSnapshot/snapshots" \
  --data-urlencode "date_start=2026-11-04" \
  --data-urlencode "date_end=2026-11-04" \
  --data-urlencode "timezone=Asia/Seoul"
```

### 응답에서 볼 것

- [ ] `rows` 에 `qCharge`, `fare1`, `fare1AgencyCode` 키가 있는지 (`qcharge` 로 깨지지 않았는지)
- [ ] `total` 이 `rows` 건수와 같은지
- [ ] 의도하지 않은 키(부모 `AbstractVo` 필드 등)가 섞이지 않았는지
- [ ] `capturedAt` 이 `2026-11-04T16:19:30+09:00` 형태인지
- [ ] `fare2` 등 값이 없는 경쟁운임이 `null` 로 나오는지
- [ ] 하루 조회일 때 `business_date` 가 내려오는지 (프론트 실시간 탭이 검사함)

### C-1. 데이터량 (중요)

프론트 계약은 **조회 기간 전체를 한 번에 응답**하는 방식. 하루 수만 건 이상이면 응답이 수십 MB 가 되어 사용 불가.
실제 건수를 확인하고, 크면 서버 집계 API 로 확장 필요 (별도 작업).

---

## D. 운영 반영 전 필수

| 항목 | 내용 |
|---|---|
| 인증/인가 | 내부 DB 를 조회하는 API. 포털 SSO 적용 범위 확인, 사용자 권한별 조회 범위 검증 필요 (프론트 계약에도 명시). Controller 주석에 TODO |
| 응답 크기 | 기간 상한이 없음. 필요하면 기간 상한(예: 31일) 검사 추가 (현재는 "유효성 검사 없음" 요구사항에 따라 미구현) |
| 로그 | JDBC 가 `p6spy` 를 사용 → SQL/바인딩 값이 로그에 남음. 운영 로그 레벨 확인 |
| 날짜 형식 오류 | `YYYY-MM-DD` 가 아니면 `STR_TO_DATE` 가 NULL → 조회 0건 (오류 없음). 프론트는 항상 올바른 형식으로 전송 |

---

## E. 알려진 한계 / 개선 제안

- `fare1~20`, `fareN_agency_code/name` **반복 컬럼 60개**는 정규화 대상. `FLIGHT_PRICE_COMPETITOR` 테이블로 분리하면 VO/SQL 이 크게 단순해짐
- 경쟁사 운임/순위는 영업상 민감 정보 → 노출 권한 범위 확정 필요
- 편도/다구간의 목적지는 테이블에 컬럼이 없어 프론트가 `return_airport` 로만 판단 (왕복만 정확)

---

## 요약: 지금 당장 할 일 3가지

1. ~~price 데이터소스용 Mapper 어노테이션~~ → `@PriceMapper` 로 반영 완료 (A-1). **XML 위치(`mapperLocations`)만 확인**
2. **파일 7개 최신본으로 교체** (A-2) + **`captured_at` 인덱스 생성** (A-3)
3. **프론트팀에 `endpoint` 변경 요청** (A-4)
