CREATE TABLE IF NOT EXISTS tm_user(id VARCHAR(40) PRIMARY KEY,email VARCHAR(254) NOT NULL UNIQUE,nick_name VARCHAR(80) NOT NULL,icon VARCHAR(500) DEFAULT '',role VARCHAR(20) DEFAULT 'USER',created_at BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS tm_user_info(user_id VARCHAR(40) PRIMARY KEY,city VARCHAR(80),introduce VARCHAR(200),birthday VARCHAR(30));
CREATE TABLE IF NOT EXISTS tm_staff_session(user_id VARCHAR(40),session_id VARCHAR(40),PRIMARY KEY(user_id,session_id));


CREATE TABLE IF NOT EXISTS tm_category(id VARCHAR(40) PRIMARY KEY,name VARCHAR(80),sort INT);
CREATE TABLE IF NOT EXISTS tm_venue(id VARCHAR(40) PRIMARY KEY,name VARCHAR(150) NOT NULL,city VARCHAR(60) NOT NULL,address VARCHAR(300) NOT NULL,longitude DOUBLE,latitude DOUBLE,description VARCHAR(1000),images TEXT);
CREATE TABLE IF NOT EXISTS tm_event(id VARCHAR(40) PRIMARY KEY,category_id VARCHAR(40) NOT NULL,venue_id VARCHAR(40) NOT NULL,title VARCHAR(180) NOT NULL,description TEXT,cover VARCHAR(500),images TEXT,status VARCHAR(30) NOT NULL,refund_allowed BOOLEAN DEFAULT FALSE,refund_deadline BIGINT,theme VARCHAR(30) DEFAULT 'violet',created_at BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS tm_session(id VARCHAR(40) PRIMARY KEY,event_id VARCHAR(40) NOT NULL,name VARCHAR(120),start_at BIGINT NOT NULL,end_at BIGINT NOT NULL,entry_start BIGINT NOT NULL,entry_end BIGINT NOT NULL,capacity INT NOT NULL);

CREATE TABLE IF NOT EXISTS tm_ticket_type(id VARCHAR(40) PRIMARY KEY,session_id VARCHAR(40) NOT NULL,name VARCHAR(80) NOT NULL,price_cent BIGINT NOT NULL,stock_total INT NOT NULL,sale_start BIGINT NOT NULL,sale_end BIGINT NOT NULL,status VARCHAR(20) NOT NULL);
CREATE TABLE IF NOT EXISTS tm_stock(ticket_type_id VARCHAR(40) PRIMARY KEY,available INT NOT NULL);

CREATE TABLE IF NOT EXISTS tm_reservation(id VARCHAR(40) PRIMARY KEY,user_id VARCHAR(40) NOT NULL,ticket_type_id VARCHAR(40) NOT NULL,idem_key VARCHAR(64) NOT NULL,state VARCHAR(30) NOT NULL,reason VARCHAR(60),order_id VARCHAR(40),created_at BIGINT NOT NULL,deadline BIGINT NOT NULL,UNIQUE(user_id,idem_key));
CREATE TABLE IF NOT EXISTS tm_slot(user_id VARCHAR(40),ticket_type_id VARCHAR(40),reservation_id VARCHAR(40),PRIMARY KEY(user_id,ticket_type_id));
CREATE TABLE IF NOT EXISTS tm_order(id VARCHAR(40) PRIMARY KEY,reservation_id VARCHAR(40) NOT NULL UNIQUE,user_id VARCHAR(40) NOT NULL,ticket_type_id VARCHAR(40) NOT NULL,session_id VARCHAR(40) NOT NULL,event_id VARCHAR(40) NOT NULL,event_title VARCHAR(180),session_name VARCHAR(120),type_name VARCHAR(80),amount_cent BIGINT NOT NULL,status VARCHAR(30) NOT NULL,created_at BIGINT NOT NULL,expire_at BIGINT NOT NULL,paid_at BIGINT,closed_at BIGINT,close_reason VARCHAR(120));

CREATE TABLE IF NOT EXISTS tm_outbox(id VARCHAR(40) PRIMARY KEY,kind VARCHAR(40) NOT NULL,aggregate_id VARCHAR(40) NOT NULL,status VARCHAR(20) NOT NULL,created_at BIGINT NOT NULL,next_at BIGINT NOT NULL,attempts INT NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS tm_payment(id VARCHAR(40) PRIMARY KEY,order_id VARCHAR(40),user_id VARCHAR(40),idem_key VARCHAR(64),amount_cent BIGINT,status VARCHAR(30),transaction_id VARCHAR(80) UNIQUE,created_at BIGINT,UNIQUE(user_id,idem_key));
CREATE TABLE IF NOT EXISTS tm_ticket(id VARCHAR(40) PRIMARY KEY,order_id VARCHAR(40) NOT NULL UNIQUE,user_id VARCHAR(40),session_id VARCHAR(40),qr_payload VARCHAR(128) NOT NULL UNIQUE,status VARCHAR(30),used_at BIGINT);
CREATE TABLE IF NOT EXISTS tm_verification(id VARCHAR(40) PRIMARY KEY,ticket_id VARCHAR(40) NOT NULL UNIQUE,staff_id VARCHAR(40),request_key VARCHAR(64),verified_at BIGINT,UNIQUE(staff_id,request_key));
CREATE TABLE IF NOT EXISTS tm_callback(event_id VARCHAR(80) PRIMARY KEY,payload_hash VARCHAR(80),created_at BIGINT);
CREATE TABLE IF NOT EXISTS tm_refund(id VARCHAR(40) PRIMARY KEY,order_id VARCHAR(40) NOT NULL UNIQUE,user_id VARCHAR(40),idem_key VARCHAR(64),amount_cent BIGINT,status VARCHAR(30),reason VARCHAR(300),transaction_id VARCHAR(80) UNIQUE,created_at BIGINT,UNIQUE(user_id,idem_key));

CREATE TABLE IF NOT EXISTS tm_post(id VARCHAR(40) PRIMARY KEY,event_id VARCHAR(40),user_id VARCHAR(40),title VARCHAR(180),content TEXT,images TEXT,liked_count INT DEFAULT 0,created_at BIGINT);
CREATE TABLE IF NOT EXISTS tm_like(post_id VARCHAR(40),user_id VARCHAR(40),liked_at BIGINT,PRIMARY KEY(post_id,user_id));
CREATE TABLE IF NOT EXISTS tm_follow(user_id VARCHAR(40),follow_user_id VARCHAR(40),created_at BIGINT,PRIMARY KEY(user_id,follow_user_id));
CREATE TABLE IF NOT EXISTS tm_sign(user_id VARCHAR(40),sign_date VARCHAR(10),PRIMARY KEY(user_id,sign_date));
CREATE TABLE IF NOT EXISTS tm_file(id VARCHAR(40) PRIMARY KEY,owner_id VARCHAR(40),purpose VARCHAR(20),object_key VARCHAR(100),mime VARCHAR(50),size_bytes BIGINT,status VARCHAR(20),created_at BIGINT);
CREATE TABLE IF NOT EXISTS tm_file_ref(file_id VARCHAR(40),resource_id VARCHAR(40),PRIMARY KEY(file_id,resource_id));
