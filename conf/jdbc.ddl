CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS member_event (
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
	session_id VARCHAR,
	start_ts TIMESTAMP,
	end_ts TIMESTAMP, 
	link_name VARCHAR,
	link_address VARCHAR,
	mode VARCHAR, 
	broker_address VARCHAR,
	PRIMARY KEY (link_name, start_ts)
);

CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS req_message (
	ts TIMESTAMP,
	inbound BOOLEAN,
	link_name VARCHAR,
	link_address VARCHAR,
	msg_id VARCHAR,
	req_count INT,
	PRIMARY KEY (link_name, msg_id, ts)
);

CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS req_batch (
	ts TIMESTAMP,
	src_link_name VARCHAR,
	src_link_address VARCHAR,
	tgt_link_name VARCHAR,
	method VARCHAR,
	size INT
);

CREATE MEMORY TEMPORARY TABLE IF NOT EXISTS rsp_message (
	ts TIMESTAMP,
	inbound BOOLEAN,
	link_name VARCHAR,
	link_address VARCHAR,
	msg_id VARCHAR,
	rsp_count INT,
	update_count INT,
	error_count INT,
	PRIMARY KEY (link_name, msg_id, ts)
);