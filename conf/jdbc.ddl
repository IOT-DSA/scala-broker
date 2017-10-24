CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS member_events (
	ts TIMESTAMP,
	role VARCHAR,
	address VARCHAR,
	state VARCHAR,
	PRIMARY KEY (address, ts)
); 

CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS link_conn (
	ts TIMESTAMP,
	event VARCHAR, 
	session_id VARCHAR,
	link_id VARCHAR,
	link_name VARCHAR, 
	link_address VARCHAR,
	mode VARCHAR, 
	version VARCHAR, 
	compression BOOLEAN,
	broker_address VARCHAR,
	PRIMARY KEY (session_id, event, ts)
); 

CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS link_session (
	start_ts TIMESTAMP,
	end_ts TIMESTAMP, 
	link_name VARCHAR,
	link_address VARCHAR,
	mode VARCHAR, 
	broker_address VARCHAR,
	PRIMARY KEY (link_name, start_ts)
);