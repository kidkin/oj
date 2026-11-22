CREATE TABLE IF NOT EXISTS edu_rename_request (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  public_name VARCHAR(24) NOT NULL,
  state ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  pending TINYINT AS (IF(state='PENDING',1,NULL)) STORED,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  reviewed_at DATETIME DEFAULT NULL,
  reviewer VARCHAR(32) DEFAULT NULL,
  UNIQUE KEY uk_rename_pending (uid,pending),
  KEY idx_rename_queue (state,id),
  CONSTRAINT fk_rename_student FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
