CREATE TABLE IF NOT EXISTS tm_post(id VARCHAR(40) PRIMARY KEY,event_id VARCHAR(40),user_id VARCHAR(40),title VARCHAR(180),content TEXT,images TEXT,liked_count INT DEFAULT 0,created_at BIGINT);
CREATE TABLE IF NOT EXISTS tm_like(post_id VARCHAR(40),user_id VARCHAR(40),liked_at BIGINT,PRIMARY KEY(post_id,user_id));
CREATE TABLE IF NOT EXISTS tm_follow(user_id VARCHAR(40),follow_user_id VARCHAR(40),created_at BIGINT,PRIMARY KEY(user_id,follow_user_id));
CREATE TABLE IF NOT EXISTS tm_sign(user_id VARCHAR(40),sign_date VARCHAR(10),PRIMARY KEY(user_id,sign_date));
CREATE TABLE IF NOT EXISTS tm_file(id VARCHAR(40) PRIMARY KEY,owner_id VARCHAR(40),purpose VARCHAR(20),object_key VARCHAR(100),mime VARCHAR(50),size_bytes BIGINT,status VARCHAR(20),created_at BIGINT);
CREATE TABLE IF NOT EXISTS tm_file_ref(file_id VARCHAR(40),resource_id VARCHAR(40),PRIMARY KEY(file_id,resource_id));
