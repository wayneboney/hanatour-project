# 항공권 가격 스냅샷 조회 (flightPriceSnapshot)

프론트 대시보드(`flightPricingDashboard.html`)가 ols 를 호출하면, ols 가 **항공API 를 MCI(POST)로 호출**해서 응답을 그대로 돌려주는 **중계(조회 전용)** API.
DB(MariaDB, price 데이터소스) 조회는 **항공API 쪽**에서 수행한다. (ols 에는 price 데이터소스 설정이 없음, `PriceMapper` 는 공통 모듈)

- 갱신일: 2026-11-05
- 브랜치: `claude/busy-knuth-v7bcfs`
- 기준: Java 8, 기존 `dynamicPricing` 구조 (Controller → Cbc → 항공API MCI POST 호출)

## 1. 구조

```
HTML (portal /air/**)
 └─ GET /air/flightPrice/snapshots?date_start=&date_end=&timezone=
      FlightPriceSnapshotController        파라미터 수신, Cbc 위임
        └─ FlightPriceSnapshotCbc(+Impl)   항공API MCI POST 호출 → 응답 JSON 그대로 반환
             └─ 항공API  POST /flightPriceSnapshot/air/api/service/snapshots/v1.00  (경로/요청키 확인 필요)
                  └─ MariaDB (price 데이터소스, AIR01.FLIGHT_PRICE_SNAPSHOT)
```

## 2. 파일 목록 (ols)

경로 기준: `src/main/java/com/hanatour/air/ols/flightPriceSnapshot/`

| 파일 | 역할 |
|---|---|
| `FlightPriceSnapshotController.java` | `@RestController`, `GET /air/flightPrice/snapshots` |
| `cbc/FlightPriceSnapshotCbc.java` | 인터페이스 |
| `cbc/impl/FlightPriceSnapshotCbcImpl.java` | 항공API MCI POST 호출 (`RestServiceClient`) |
| `cbc/vo/FlightPriceSnapshotReqVo.java` | 조회 조건 (`dateStart`, `dateEnd`) |

참고 자료(항공API 개발자용): `docs/reference-for-api/`

## 3. 프론트 ↔ ols API

### GET `/air/flightPrice/snapshots`

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `date_start` | O | 수집일자 시작 `YYYY-MM-DD` (KST, 포함) |
| `date_end` | O | 수집일자 종료 `YYYY-MM-DD` (KST, 포함) |
| `timezone` | | 프론트가 `Asia/Seoul` 고정 전송. ols 는 사용하지 않고 항공API 에는 `Asia/Seoul` 로 고정 전달 |

> HTML → ols 는 **GET** (프론트 `fetch` 가 GET, 요청 body 없음). ols → 항공API 는 **POST**.

프론트 HTML 의 `endpoint` 는 `/air/flightPrice/snapshots` (프론트 저장소 `air-seer/naver_snapshot/flightPricingDashboard.html` 기준). Controller 를 이 경로에 맞춤.

## 4. ols → 항공API (항공API 개발자 구현 사항)

ols 가 호출하는 항공API (**경로/요청 키는 항공API 개발자와 확정 필요**, 현재 가정). 호출 방식은 `dynamicPricing` 과 같은 **MCI POST**:

```
POST {ENDPOINT_API}/flightPriceSnapshot/air/api/service/snapshots/v1.00   (MCI Header 포함)
Body: { "dateStart": "2026-11-04", "dateEnd": "2026-11-04", "timeZone": "Asia/Seoul" }
```

항공API 가 반환해야 하는 응답 (프론트 `API_CONTRACT.md` 계약, ols 는 **가공 없이 그대로 전달**):

```json
{
  "business_date": "2026-11-04",
  "as_of": "2026-11-04T16:20:00+09:00",
  "is_complete": true,
  "total": 1,
  "rows": [ { "snapshot_id": "123", "reservation_code": "...", "base_fare": "235000.00",
              "fare1": "230000.00", "fare1_agency_code": "...", "fare1_agency_name": "...",
              "current_rank": 2, "captured_at": "2026-11-04T16:19:30+09:00", "...": "..." } ]
}
```

| 규칙 | 설명 |
|---|---|
| 조회 기준 | `captured_at` 기준 KST 영업일, 양끝 포함: `[date_start 00:00, date_end+1일 00:00)`. `dep_date` 로 조회 금지 |
| 건수 | **기간 전체를 한 번에 반환**. `total` 은 `rows` 건수와 같아야 함 (다르면 프론트가 오류 처리) |
| `business_date` | 하루 조회일 때 반드시 반환 (프론트 실시간 탭이 오늘(KST)과 일치하는지 검사) |
| 키 | snake_case/camelCase/대문자 모두 프론트가 처리. 단 `q_charge` 가 `qcharge` 로 깨지지 않게 주의 |
| 값 | 금액은 문자열, `NULL` 은 `null` 유지, `snapshot_id` 는 문자열, 시각은 `+09:00` 포함 ISO |
| `base_fare` | 세금/유류 포함 성인 1인 최종금액. `tax`/`q_charge` 를 합산하지 않음 |
| 오류 | HTTP 상태코드로 명확히 반환 (200 + 빈 rows 로 오류를 숨기지 않기) |
| 인덱스 | `captured_at` 단독 인덱스 필요 (`reference-for-api/` 스크립트) |

## 4-1. 예약·탑승객 분석 탭 필드 (최신 HTML 에서 추가됨)

최신 `flightPricingDashboard.html` 의 "예약·탑승객 분석" 탭은 **같은 API 응답의 각 행**에서 아래 필드를 추가로 읽는다 (별도 호출·별도 요청 파라미터 없음).
**모두 선택(optional)**: 없으면(`null`/생략) 화면에 "미기록"으로 표시. 단 이 필드들은 `FLIGHT_PRICE_SNAPSHOT` 테이블에 없어서 항공API 에서 예약 테이블 등과 조인해야 함.

| 키 | 의미 | 형식 규칙 (프론트가 검사) |
|---|---|---|
| `res_id` | 예약 ID | 문자열 |
| `res_com_cd` | 예약 공통 코드 | 문자열 |
| `res_cd` | 항공 예약 코드 | 문자열 |
| `unfy_res_cd` | 항공 통합 예약 코드 | 문자열 |
| `afcn_mem_grad_nm` | 제휴사 멤버십 등급 | 문자열 (멤버십 필터에 사용) |
| `gds_dv_cd` | GDS 구분 | 문자열 (GDS 필터에 사용) |
| `gds1_pnr_num`, `gds_pnr_num` | GDS PNR | 문자열 |
| `card_event_cd` | 카드 프로모션 코드 | 문자열 |
| `afcn_card_cd` | 제휴사 카드 코드 | 문자열 |
| `res_adt_cnt` | 성인 예약 인원 | **0 이상 정수** (숫자 또는 `"2"`, `"2.0"` 허용). 아니면 "형식 오류" |
| `res_chd_cnt` | 소아 예약 인원 | 위와 동일 |
| `passenger_gender_summary` | 탑승객 성별 요약 | **문자열** `"M1,F2"` (`M` 또는 `F` + 인원, 쉼표 구분). 숫자/객체면 형식 오류 |
| `passenger_age_summary` | 탑승객 성별·연령 요약 | **문자열** `"M20,1,F40,1"` = (성별+연령대, 인원) 쌍의 쉼표 나열. 연령대는 0~120 의 10 단위(`M20`=남 20대, `M0`=10세 미만), 토큰 수는 짝수 |

검증 규칙(프론트): 성별 요약 총인원 = 연령 요약 총인원 = `res_adt_cnt + res_chd_cnt` 이어야 정상 (다르면 "집계 범위 확인" 경고 표시, 화면은 계속 동작).
예약 단위 묶음 키: `reservation_code` → 없으면 `res_id` → `unfy_res_cd` → `res_cd` 순.

- 키는 camelCase 로 내려도 프론트가 변환해서 읽음 (`resAdtCnt`→`res_adt_cnt`, `gds1PnrNum`→`gds1_pnr_num`).
- 요청(`date_start`, `date_end`, `timezone`) 과 응답 봉투(`business_date`, `as_of`, `is_complete`, `total`, `rows`)는 **이전 버전과 동일**.
- ols 는 응답을 가공 없이 전달하므로 **ols 코드 수정은 필요 없음**.
- ⚠ **민감 정보**: PNR, 예약코드, 탑승객 성별/연령, 멤버십 등급이 포함됨. 이 API 를 통해 대량 노출되므로 **인증/권한 검증을 더 엄격히** 해야 하고, 분석에 불필요한 식별자(PNR 등)는 내려주지 않는 것을 검토할 것.

## 5. dynamicPricing Cbc 와 차이

| 항목 | dynamicPricing | 이번 구현 |
|---|---|---|
| 호출 | GET(`callGetApi`) + POST(MCI) | **MCI POST 만** (`callApiService` 동일) |
| 요청 본문 | `{ "targetValue": ... }` | `{ "dateStart", "dateEnd", "timeZone" }` (키 이름은 항공API 와 확정) |
| 응답 로그 | 응답 전문 로그 | 크기만 로그 (대용량 응답) |
| 예외 | Controller `@ExceptionHandler` | 핸들러 없음 (요구사항). `ApiException` 은 `IllegalStateException` 으로 변환해 전파 |
| 주입 | 생성자 `@Autowired` | `@RequiredArgsConstructor` |

## 6. 확인 필요 / 남은 작업

1. **항공API 경로/요청 키 확정** (항공API 개발자): `Cbc` 의 `SNAPSHOT_PATH` 상수와 `toRequestJson()` 의 키 이름 수정. MCI Header 값(`JobSysCd` 등)이 dynamicPricing 과 같은지도 확인
2. **항공API 응답 규격**: 4번 규칙대로 내려오는지 (특히 `total == rows`, `business_date`, `q_charge` 키)
3. **데이터량**: 기간 전체를 한 응답으로 중계하는 구조. 하루 수만 건 이상이면 응답이 커져 ols/프론트 모두 부담 → 항공API 에서 서버 집계 API 로 확장 필요
4. **타임아웃**: `RestServiceClient` 의 MCI 타임아웃 설정을 따름 (코드에서 별도 지정하지 않음). 프론트 30초, 포털 프록시 타임아웃보다 짧은지 확인
5. **인증/인가 (운영 반영 전 필수)**: 포털 SSO 적용 범위, 사용자 권한별 조회 범위 검증. Controller 주석에 TODO
6. **컴파일/실행 미검증**: 사내 라이브러리(`hntframe`, `RestServiceClient` 등)가 없는 환경에서 작성. DEV 환경에서 호출 확인 필요
7. **편도/다구간 목적지**: 테이블에 `dest_airport` 컬럼 없음 → 프론트는 `return_airport` 로만 판단 (왕복만 정확)

## 7. 구조 개선 제안

- `FARE1~20`, `FAREn_AGENCY_CODE/NAME` 반복 컬럼(60개)은 정규화 대상.
  `FLIGHT_PRICE_COMPETITOR(SNAPSHOT_ID, RANK_NO, FARE, AGENCY_CODE, AGENCY_NAME)` 로 분리하면 확장/집계/조회가 쉬움
- 경쟁사 운임·순위는 영업상 민감 정보라 노출 권한 범위 확정 필요
