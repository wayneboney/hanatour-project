# flightPriceSnapshot 적용 체크리스트 (고쳐야 할 것 / 확인할 것)

> **요건 변경 (2026-11-05)**: ols 는 price(MariaDB) 데이터소스 설정이 없어서 DB 를 직접 조회하지 않는다.
> ols 는 **항공API 를 호출해 응답을 중계**하고, DB 조회는 **항공API 개발자**가 구현한다.
> 구조/명세는 `docs/flightPriceSnapshot.md`, 항공API 참고 자료는 `docs/reference-for-api/` 참고.

- 코드 상태: 컴파일/실행 **미검증** (개발 환경에 사내 라이브러리 `hntframe`, `RestServiceClient` 등이 없음)

---

## A. ols 에서 해야 할 일

### A-1. 파일 복사 (4개)

경로 기준: `src/main/java/com/hanatour/air/ols/flightPriceSnapshot/`

| 파일 | 비고 |
|---|---|
| `FlightPriceSnapshotController.java` | `GET /air/flightPriceSnapshot/snapshots` |
| `cbc/FlightPriceSnapshotCbc.java` | `String getSnapshots(ReqVo)` |
| `cbc/impl/FlightPriceSnapshotCbcImpl.java` | 항공API GET 호출 (**전면 교체**) |
| `cbc/vo/FlightPriceSnapshotReqVo.java` | `dateStart`, `dateEnd` |

**이전에 복사해 둔 아래 파일은 삭제**: `ec/qc/FlightPriceSnapshotMapper.java`, `FlightPriceSnapshotMapper.xml`, `cbc/vo/FlightPriceSnapshotVo.java`, `bc/` 폴더 (ols 에서 DB 를 조회하지 않으므로 불필요. `@PriceMapper` 도 사용하지 않음)

### A-2. 항공API 경로 확정 후 수정

`FlightPriceSnapshotCbcImpl.java` 의 상수 한 줄:

```java
private static final String SNAPSHOT_PATH = "/flightPriceSnapshot/air/api/service/snapshots/v1.00"; // 가정
```

항공API 개발자가 정한 실제 경로로 변경. 파라미터명(`date_start`, `date_end`, `timezone`)과 GET 방식도 같이 확인.

### A-3. 의존 클래스 확인

`dynamicPricing` 의 `DynamicPricingCbcImpl` 과 같은 빈을 주입받음: `ServiceEndpoint`, `WebClient`.
같은 모듈에서 `DynamicPricingCbcImpl` 이 정상 동작하면 추가 설정 불필요.

---

## B. 항공API 개발자에게 확인/전달

이미 전달했다고 하셨으므로 **아래가 반영됐는지 확인용**. 상세는 `docs/flightPriceSnapshot.md` 4번.

- [ ] 경로/파라미터/HTTP 메서드(GET) 확정 → ols `SNAPSHOT_PATH` 반영
- [ ] 응답 `{business_date, as_of, is_complete, total, rows}`, `total == rows.length`
- [ ] 하루 조회일 때 `business_date` 반환 (프론트 실시간 탭 검사)
- [ ] `q_charge` 키가 `qcharge` 로 깨지지 않을 것 (Jackson getter 규칙)
- [ ] 금액 문자열, `null` 유지, `snapshot_id` 문자열, 시각 `+09:00` ISO
- [ ] `captured_at` 기준 KST 영업일(양끝 포함), `dep_date` 로 조회 금지
- [ ] `captured_at` 단독 인덱스 (`docs/reference-for-api/FLIGHT_PRICE_SNAPSHOT.index.mariadb.sql`)
- [ ] 오류는 HTTP 상태코드로 반환

---

## C. 첫 호출 때 확인 (DEV)

```bash
curl -G "http://127.0.0.1:8081/air/flightPriceSnapshot/snapshots" \
  --data-urlencode "date_start=2026-11-04" \
  --data-urlencode "date_end=2026-11-04" \
  --data-urlencode "timezone=Asia/Seoul"
```

- [ ] 항공API 가 호출되는지 (ols 로그 `[FLIGHT-PRICE-SNAPSHOT] GET ...`)
- [ ] 응답이 항공API 응답 그대로인지 (ols 는 가공하지 않음)
- [ ] 프론트 화면이 정상 표시되는지 (`PARTIAL_RESPONSE` 등 오류 없음)
- [ ] 응답 시간이 25초 이내인지

### 데이터량 (중요)

기간 전체를 한 응답으로 중계하는 구조. 하루 수만 건 이상이면 ols 메모리/응답 시간/프론트 모두 부담 → 항공API 에서 서버 집계 API 로 확장 필요.

---

## D. 운영 반영 전 필수

| 항목 | 내용 |
|---|---|
| 인증/인가 | 포털 SSO 적용 범위, 사용자 권한별 조회 범위 검증 (프론트 계약에도 명시). Controller 주석에 TODO |
| 타임아웃 | ols 25초, 프론트 30초. 포털 프록시 타임아웃과 항공API 응답 시간 확인 |
| 예외 처리 | 요구사항에 따라 `@ExceptionHandler` 없음. 항공API 오류/타임아웃은 프레임워크 기본 오류로 나감 (프론트는 HTTP 오류로 표시) |

---

## 요약: 지금 할 일

1. 파일 4개 복사, 옛 DB 조회 파일 삭제 (A-1)
2. 항공API 개발자에게 **경로 확정** → `SNAPSHOT_PATH` 수정 (A-2)
3. DEV 에서 curl 로 호출 확인 (C)
