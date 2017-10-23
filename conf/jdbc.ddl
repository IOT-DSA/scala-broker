CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS member_events (
	ts TIMESTAMP,
	role VARCHAR,
	address VARCHAR,
	state VARCHAR,
	PRIMARY KEY (address, ts)
); 