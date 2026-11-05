# 항공권 가격 스냅샷 조회 (flightPriceSnapshot)

프론트 대시보드(`flightPricingDashboard.html`)가 호출하면 ols가 내부 Oracle DB(MyBatis)를 조회해 JSON으로 돌려주는 **조회 전용** API.
프론트팀 `API_CONTRACT.md` 기준으로 구현.

- 갱신일: 2026-11-05
- 브랜치: `claude/busy-knuth-v7bcfs`
- 기준: Java 8, Oracle, 기존 `dynamicPricing` 구조 (Controller → Cbc), 단순 조회라 Bc 생략

## 1. 구조

```
HTML (portal /air/**)
 └─ GET /air/flightPriceSnapshot/snapshots?date_start=&date_end=&timezone=
      FlightPriceSnapshotController        파라미터 수신, Cbc 위임
        └─ FlightPriceSnapshotCbc(+Impl)   Mapper 호출 → 응답 JSON 조립
             └─ FlightPriceSnapshotMapper  @AirMapper (ec.qc)
                  └─ FlightPriceSnapshotMapper.xml  Oracle SQL
```

## 2. 파일 목록

경로 기준: `src/main/java/com/hanatour/air/ols/flightPriceSnapshot/`

| 파일 | 역할 |
|---|---|
| `FlightPriceSnapshotController.java` | `@RestController`, `GET /air/flightPriceSnapshot/snapshots` |
| `cbc/FlightPriceSnapshotCbc.java` | 인터페이스 |
| `cbc/impl/FlightPriceSnapshotCbcImpl.java` | Mapper 호출, 응답 JSON 조립 |
| `cbc/vo/FlightPriceSnapshotReqVo.java` | 조회 조건 (`dateStart`, `dateEnd`) |
| `cbc/vo/FlightPriceSnapshotVo.java` | 스냅샷 1행 (81개 필드) |
| `ec/qc/FlightPriceSnapshotMapper.java` | MyBatis Mapper 인터페이스 |
| `src/main/resources/mapper/com/hanatour/air/ols/flightPriceSnapshot/FlightPriceSnapshotMapper.xml` | SQL |
| `sql/FLIGHT_PRICE_SNAPSHOT.oracle.sql` | Oracle DDL 확인용 초안 (인덱스 포함) |

## 3. API

### GET `/air/flightPriceSnapshot/snapshots`

> 프론트 기본 `endpoint` 는 `/air/dynamicPricing/snapshots` (제안 경로) 이므로
> **프론트 `PRICING_DASHBOARD_CONFIG.endpoint` 를 `/air/flightPriceSnapshot/snapshots` 로 변경 요청 필요.**

| 파라미터 | 필수 | 설명 |
|---|---|---|
| `date_start` | O | 수집일자 시작 `YYYY-MM-DD` (KST, 포함) |
| `date_end` | O | 수집일자 종료 `YYYY-MM-DD` (KST, 포함) |
| `timezone` | | 프론트가 `Asia/Seoul` 고정으로 전송. 서버는 사용하지 않음 (KST 기준) |

조회 범위: `[date_start 00:00 KST, date_end 다음날 00:00 KST)` — `captured_at` 기준. 예약 출발일(`dep_date`)로 조회하지 않음.

응답:

```json
{
  "business_date": "2026-11-04",
  "as_of": "2026-11-04T16:20:00+09:00",
  "is_complete": true,
  "total": 1,
  "rows": [
    {
      "snapshotId": "12345", "reservationCode": "...", "tripType": "ROUND_TRIP", "isDirectOnly": 1,
      "depDate": "2026-12-15", "depAirport": "ICN", "...": "...",
      "baseFare": "235000.00", "tax": "45000.00", "qCharge": "15000.00",
      "fare1": "230000.00", "fare1AgencyCode": "...", "fare1AgencyName": "...",
      "fare2": null, "fare2AgencyCode": null, "fare2AgencyName": null,
      "currentRank": 2,
      "capturedAt": "2026-11-04T16:19:30+09:00", "createdAt": "2026-11-04T16:19:31+09:00"
    }
  ]
}
```

- `business_date`: **하루 조회일 때만** 반환 (프론트 실시간 탭이 오늘(KST)과 일치하는지 검사). 기간 조회면 `null`
- `total` = `rows` 건수 (프론트는 불일치 시 `PARTIAL_RESPONSE` 오류 처리). **건수 제한 없이 기간 전체 반환**
- 행 키는 camelCase (`snapshotId`, `baseFare`, `fare1AgencyCode` ...). 프론트가 snake_case/camelCase/대문자를 같은 키로 변환해서 읽음
- `qCharge`: 필드 기준 직렬화로 `q_charge` 와 동일하게 인식 (getter 기준이면 `qcharge` 로 깨짐)
- 금액: 문자열(`TO_CHAR`), `NULL` 은 `null` 유지, `base_fare` 는 세금/유류 포함 최종금액 (DB 값을 그대로 전달, 합산 안 함)
- 시각: `+09:00` 오프셋 포함 ISO 형식 (DB 가 KST 저장이라는 전제)
- `snapshotId`: 문자열 (BIGINT 정밀도 보존)
- `isDirectOnly`: DB `Y/N` → `1/0`

## 4. 검증

입력 유효성 검사는 하지 않는다. (단순 조회 요구사항)

- `date_start`, `date_end` 누락 시 Spring 이 400 처리
- 날짜 형식이 `YYYY-MM-DD` 가 아니면 Oracle `TO_DATE` 오류 → 예외 전파
- 기간 상한 없음, 건수 제한 없음 → 기간이 길면 응답이 매우 커질 수 있음 (7번 참고)

## 5. SQL 요약

- 대상 테이블: `AIR01.FLIGHT_PRICE_SNAPSHOT` (스키마/테이블명은 **가정**)
- 조건: `CAPTURED_AT >= date_start` AND `CAPTURED_AT < date_end + 1`
- 정렬: `CAPTURED_AT DESC, SNAPSHOT_ID DESC`
- `${}` 미사용, 전부 `#{}` 바인딩
- **인덱스**: 기존 `(RESERVATION_CODE, CAPTURED_AT)` 는 예약코드 없는 기간 조회에 사용할 수 없음 → `IDX_FPS_CAPTURED_AT (CAPTURED_AT)` 추가 필요 (DDL 초안에 반영)

## 6. 샘플 대비 차이

| 항목 | 샘플 | 이번 구현 |
|---|---|---|
| 호출 방식 | `@ServiceMapping` / `@RestController` POST | `@RestController` + `@GetMapping` (프론트 계약) |
| 계층 | Cbc → Bc → Mapper | Cbc → Mapper (Bc 생략) |
| 주입 | `@Autowired` 필드 | `@RequiredArgsConstructor` 생성자 주입 |
| 예외 | Cbc에서 catch 후 debug 로그만 (삼킴) | 삼키지 않고 전파 |
| 조회기간 | `BETWEEN ... + 0.99999` | `>= 시작일` AND `< 종료일 + 1` |
| VO | getter/setter 직접 작성 | Lombok `@Getter @Setter` + Jackson 필드 기준 직렬화 |

## 7. 확인 필요 / 남은 작업

1. **데이터량 (중요)**: 기간 전체를 한 응답으로 반환하는 계약. 하루 스냅샷이 수만 건 이상이면 응답이 수십 MB가 되어 사용 불가 → 서버 집계 API로 확장 필요
2. **스키마/테이블/컬럼명**: `AIR01.FLIGHT_PRICE_SNAPSHOT` 은 가정. 실제 이름과 대조
3. **DB 시각 저장 기준**: KST 저장 전제. UTC 저장이면 기간 경계와 `+09:00` 표기 변환 필요
4. **`IS_DIRECT_ONLY` 컬럼 타입**: Oracle DDL 초안은 `CHAR(1) 'Y'/'N'`. 실제가 숫자 0/1이면 SQL 의 `DECODE` 수정
5. **Oracle DDL / 인덱스**: 초안이므로 DBA 검토 필요 (IDENTITY 는 12c+)
6. **SQL 직접 실행 검증**: 개발 환경에 Oracle이 없어 미검증
7. **JSON 직렬화**: 응답에 부모 `AbstractVo` 의 필드가 섞이는지 첫 호출 때 확인
8. **인증/인가 (운영 반영 전 필수)**: 사용자 권한별 조회 범위 검증 필요. 포털 SSO 적용 범위 확인
9. **프론트 요청**: `endpoint` 를 `/air/flightPriceSnapshot/snapshots` 로 변경
10. **편도/다구간 목적지**: 테이블에 `dest_airport` 컬럼이 없음. 왕복만 `return_airport` 로 목적지 판단 가능

## 8. 구조 개선 제안

- `FARE1~20`, `FAREn_AGENCY_CODE/NAME` 반복 컬럼(60개)은 정규화 대상.
  `FLIGHT_PRICE_COMPETITOR(SNAPSHOT_ID, RANK_NO, FARE, AGENCY_CODE, AGENCY_NAME)` 로 분리하면 확장/집계/조회가 쉬움
- 경쟁사 운임·순위는 영업상 민감 정보라 노출 권한 범위 확정 필요
