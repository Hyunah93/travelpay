-- 공용 초기화 SQL(01-service-schemas.sql) 다음에 한 번 실행됩니다. 쿼리 모양별 실행 횟수·시간을 모읍니다.
-- 공용 초기화가 public 스키마 권한을 PUBLIC에서 회수하므로 조회는 관리자 계정으로 합니다.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
