CREATE TABLE IF NOT EXISTS edu_user_learning_start (
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  first_submit_at DATETIME NOT NULL,
  PRIMARY KEY (uid),
  CONSTRAINT fk_edu_learning_start_user FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO edu_user_learning_start(uid,first_submit_at)
SELECT j.uid,MIN(j.submit_time) FROM judge j JOIN user_info u ON u.uuid=j.uid
WHERE j.submit_time IS NOT NULL GROUP BY j.uid
ON DUPLICATE KEY UPDATE first_submit_at=LEAST(first_submit_at,VALUES(first_submit_at));
