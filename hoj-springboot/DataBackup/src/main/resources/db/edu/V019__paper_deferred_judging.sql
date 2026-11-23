CREATE TABLE IF NOT EXISTS edu_paper_deferred (
  cid BIGINT UNSIGNED PRIMARY KEY,
  CONSTRAINT fk_paper_deferred FOREIGN KEY (cid) REFERENCES edu_contest_paper(cid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_paper_code (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cid BIGINT UNSIGNED NOT NULL,
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  attempt_key BIGINT NOT NULL,
  pid BIGINT UNSIGNED NOT NULL,
  cpid BIGINT UNSIGNED NOT NULL,
  language VARCHAR(100) NOT NULL,
  code MEDIUMTEXT NOT NULL,
  compile_state VARCHAR(12) NOT NULL DEFAULT 'QUEUED',
  diagnostics TEXT NULL,
  grade_state VARCHAR(12) NULL,
  score INT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_until DATETIME(3) NULL,
  KEY idx_paper_compile (compile_state,id),
  KEY idx_paper_grade (grade_state,id),
  KEY idx_paper_code_attempt (cid,uid,attempt_key,cpid,id),
  CONSTRAINT fk_paper_code_policy FOREIGN KEY (cid) REFERENCES edu_paper_deferred(cid) ON DELETE CASCADE,
  CONSTRAINT fk_paper_code_user FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_paper_grade (
  cid BIGINT UNSIGNED NOT NULL,
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  attempt_key BIGINT NOT NULL,
  requested_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  published_at DATETIME(3) NULL,
  PRIMARY KEY(cid,uid,attempt_key),
  CONSTRAINT fk_paper_grade_policy FOREIGN KEY (cid) REFERENCES edu_paper_deferred(cid) ON DELETE CASCADE,
  CONSTRAINT fk_paper_grade_user FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
