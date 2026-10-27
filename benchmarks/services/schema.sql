-- Benchmark-only database. All three backends run identical SQL statements.
CREATE TABLE bench_lookup (id INTEGER PRIMARY KEY, payload VARCHAR(64) NOT NULL);
INSERT INTO bench_lookup (id, payload) VALUES (1, 'ok');
CREATE TABLE bench_counter (id INTEGER PRIMARY KEY, counter_value BIGINT NOT NULL DEFAULT 0);
INSERT INTO bench_counter (id, value) VALUES (1,0),(2,0);
CREATE TABLE bench_events (id BIGSERIAL PRIMARY KEY, payload VARCHAR(64) NOT NULL);
