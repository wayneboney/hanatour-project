# 항공권 가격 스냅샷 조회 (flightPriceSnapshot)

HTML 화면이 URL을 호출하면 ols가 내부 Oracle DB(MyBatis)를 조회해 JSON으로 돌려주는 조회 전용 API.

- 작성일: 2026-11-04
- 브랜치: `claude/busy-knuth-v7bcfs`
- 기준: Java 8, Oracle, 기존 `dynamicPricing` 구조 (Controller → Cbc), 단순 조회라 Bc 생략

## 1. 구조

```
HTML
 └─ POST /air/flightPriceSnapshot/list
      FlightPriceSnapshotController        요청/응답 입출력만 담당
        └─ FlightPriceSnapshotCbc(+Impl)   입력 검증 → Mapper 호출 → JSON 변환
             └─ FlightPriceSnapshotMapper  @AirMapper (ec.qc)
                  └─ FlightPriceSnapshotMapper.xml  Oracle SQL
```

## 2. 파일 목록

경로 기준: `src/main/java/com/hanatour/air/ols/flightPriceSnapshot/`

| 파일 | 역할 |
|---|---|
| `FlightPriceSnapshotController.java` | `@RestController`, `/air/flightPriceSnapshot/list` |
| `cbc/FlightPriceSnapshotCbc.java` | 인터페이스 (`String getSnapshotList(ReqVo)`) |
| `cbc/impl/FlightPriceSnapshotCbcImpl.java` | 검증, Mapper 호출, JSON 직렬화 |
| `cbc/vo/FlightPriceSnapshotReqVo.java` | 요청 VO (Mapper 파라미터 겸용) |
| `cbc/vo/FlightPriceSnapshotVo.java` | 목록 결과 1행 |
| `ec/qc/FlightPriceSnapshotMapper.java` | MyBatis Mapper 인터페이스 |
| `src/main/resources/mapper/com/hanatour/air/ols/flightPriceSnapshot/FlightPriceSnapshotMapper.xml` | SQL |
| `sql/FLIGHT_PRICE_SNAPSHOT.oracle.sql` | Oracle DDL 확인용 초안 (MariaDB DDL 변환) |

## 3. API 명세

### POST `/air/flightPriceSnapshot/list`

요청:

```json
{
  "reservationCode": "ABC123",
  "startDt": "20261101",
  "endDt": "20261104",
  "maxRows": 50
}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `reservationCode` | O | 영문/숫자/`-`/`_` 1~50자 |
| `startDt` | | 수집일자 시작 (YYYYMMDD, 실제 존재하는 날짜) |
| `endDt` | | 수집일자 종료 (YYYYMMDD, 당일 23:59:59 까지 포함) |
| `maxRows` | | 조회 건수. 생략 시 50, 허용 범위 1~200 |

응답 (수집시각 최신순):

```json
{
  "totalCount": 120,
  "list": [
    {
      "totalCount": 120,
      "snapshotId": "1001",
      "reservationCode": "ABC123",
      "tripType": "ROUND_TRIP",
      "directOnlyYn": "Y",
      "depDate": "2026-12-01",
      "depAirport": "ICN",
      "depAirlineCode": "KE",
      "depFlightNo": "123",
      "returnDate": "2026-12-08",
      "returnAirport": "NRT",
      "returnAirlineCode": "KE",
      "returnFlightNo": "124",
      "promoCardCode": null,
      "seatClass": "ECONOMY",
      "passengerType": "ADULT",
      "baseFare": "350000.00",
      "tax": "50000.00",
      "fuelSurcharge": "30000.00",
      "currentRank": "3",
      "capturedAt": "2026-11-04 09:30:00"
    }
  ]
}
```

- `totalCount`: `maxRows`로 자르기 전 전체 건수
- 날짜/금액은 SQL에서 `TO_CHAR`로 문자열 변환 (금액 형식 `FM999999999990.00`)

## 4. 검증 규칙 (CbcImpl)

| 항목 | 규칙 |
|---|---|
| 예약코드 | `^[A-Za-z0-9_-]{1,50}$` |
| 날짜 | `uuuuMMdd` STRICT (존재하지 않는 날짜 거부), 시작일 > 종료일이면 오류 |
| `maxRows` | 미입력 → 50, 1~200 외에는 오류 |
| 오류 | `IllegalArgumentException` (DB 예외는 삼키지 않고 전파) |

## 5. SQL 요약

- 대상 테이블: `AIR01.FLIGHT_PRICE_SNAPSHOT` (스키마/테이블명은 **가정**)
- 조건: `RESERVATION_CODE = #{reservationCode}` + 수집일자 기간(`CAPTURED_AT`, 선택)
- 정렬: `CAPTURED_AT DESC, SNAPSHOT_ID DESC`
- 건수 제한: 바깥 쿼리 `ROWNUM <= #{maxRows}` (Oracle 11g 호환)
- `${}` 미사용, 전부 `#{}` 바인딩
- 인덱스: `(RESERVATION_CODE, CAPTURED_AT)` 활용

## 6. 샘플(DcdBspCardCrtt, DynamicPricing) 대비 차이

| 항목 | 샘플 | 이번 구현 |
|---|---|---|
| 호출 방식 | `@ServiceMapping` (넥사크로) / `@RestController` (dynamicPricing) | `@RestController` + `@PostMapping` |
| 계층 | Cbc → Bc → Mapper | Cbc → Mapper (Bc 생략) |
| 주입 | `@Autowired` 필드 | `@RequiredArgsConstructor` 생성자 주입 |
| 예외 | Cbc에서 catch 후 debug 로그만 (삼킴) | 삼키지 않고 전파 |
| 조회기간 | `BETWEEN ... + 0.99999` | `>= 시작일` AND `< 종료일 + 1` |
| 건수 제한 | 없음 | `ROWNUM` + 상한 200 |
| VO | getter/setter 직접 작성 | Lombok `@Getter @Setter` (DynamicPricingVo 방식) |

## 7. 확인 필요 / 남은 작업

1. **스키마/테이블명**: `AIR01.FLIGHT_PRICE_SNAPSHOT` 은 가정. XML 38행 `FROM` 수정 필요 시 수정
2. **컬럼명**: `Q_CHARGE` 등 DDL 원본 이름 사용. 실제 Oracle 컬럼명과 대조
3. **Oracle DDL**: `sql/FLIGHT_PRICE_SNAPSHOT.oracle.sql` 은 MariaDB DDL 변환 초안. DBA 검토 필요 (IDENTITY는 12c+)
4. **SQL 직접 실행 검증**: 개발 환경에 Oracle이 없어 미검증. 실제 DB에서 실행 확인 필요
5. **JSON 직렬화**: VO가 `AbstractVo` 상속. `ObjectMapper` 직렬화 결과에 불필요한 필드가 섞이는지 첫 호출 때 확인
6. **MyBatis 매핑**: 언더스코어 → 카멜 자동 매핑 설정 전제 (`DIRECT_ONLY_YN` → `directOnlyYn`)
7. **XML 위치**: `mapper/com/hanatour/air/ols/...` 중첩 폴더 가정. 사내 `mapperLocations` 설정과 대조
8. **인증/인가 (운영 반영 전 필수)**: 내부 DB를 직접 조회하는 API. 사내 인증 또는 접근 IP 제한 필요. Controller 주석에 TODO로 표시
9. **경쟁 운임 컬럼**: 현재 목록에는 `FARE1~20` 이 없음. 화면에서 경쟁사 가격이 필요하면 SQL/VO에 추가 필요 (용도 확정 후 결정)
10. **HTML**: 아직 미제공. 받으면 응답 필드 기준으로 맞춤

## 8. 구조 개선 제안

- `FARE1~20`, `FAREn_AGENCY_CODE/NAME` 반복 컬럼(60개)은 정규화 대상.
  `FLIGHT_PRICE_COMPETITOR(SNAPSHOT_ID, RANK_NO, FARE, AGENCY_CODE, AGENCY_NAME)` 로 분리하면 확장/집계/조회가 쉬움
- 경쟁사 운임·순위는 영업상 민감 정보라 노출 권한 범위 확정 필요
