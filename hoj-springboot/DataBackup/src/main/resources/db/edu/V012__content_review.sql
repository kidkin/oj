CREATE TABLE IF NOT EXISTS edu_review_rules (
    revision BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    words TEXT NOT NULL,
    editor VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO edu_review_rules(revision,words,editor)
SELECT 1,CONCAT_WS(CHAR(10),'共产党','共產黨','共产主义','共產主義','中共'),'system'
WHERE NOT EXISTS (SELECT 1 FROM edu_review_rules WHERE revision=1);
CREATE TABLE IF NOT EXISTS edu_content_review (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    fingerprint CHAR(64) CHARACTER SET ascii NOT NULL UNIQUE,
    kind VARCHAR(10) NOT NULL,
    uid VARCHAR(64) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
    submit_id BIGINT NULL,
    subject VARCHAR(255) NOT NULL,
    hits TEXT NOT NULL,
    excerpt MEDIUMTEXT NOT NULL,
    revision BIGINT NOT NULL,
    decision VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    reviewer VARCHAR(64) CHARACTER SET ${userCharset} COLLATE ${userCollation} NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX review_queue(decision,id),
    INDEX review_submit(submit_id,decision),
    INDEX review_author(uid,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
