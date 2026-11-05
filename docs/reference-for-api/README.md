# 항공API 개발자 참고 자료

ols 는 price(MariaDB) 데이터소스 설정이 없어서, **DB 조회는 항공API 에서 수행**하고 ols 는 호출만 한다.
처음에 ols 에서 직접 DB 를 조회하는 방식으로 작성했던 코드를 참고용으로 보관한 것. **ols 에서는 사용하지 않음.**

| 파일 | 내용 |
|---|---|
| `FlightPriceSnapshotMapper.xml` | MariaDB SQL (기간 조회, 전체 컬럼, 시각 `+09:00` ISO, 금액 문자열). 미실행 검증 |
| `FlightPriceSnapshotVo.java.txt` | 응답 행 VO (81개 필드). Jackson 이 getter 가 아닌 **필드 기준**으로 직렬화하도록 `@JsonAutoDetect` 사용 (`qCharge` 가 `qcharge` 로 깨지는 문제 방지) |
| `FLIGHT_PRICE_SNAPSHOT.index.mariadb.sql` | 기간 조회용 인덱스 `captured_at` (없으면 풀스캔) |

응답 규격은 프론트팀 `API_CONTRACT.md` 기준 (`docs/flightPriceSnapshot.md` 3번 참고).
