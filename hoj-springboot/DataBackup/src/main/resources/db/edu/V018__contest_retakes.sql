CREATE TABLE IF NOT EXISTS edu_paper_retake (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  cid BIGINT UNSIGNED NOT NULL,
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  reset_by VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  reset_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  started_at DATETIME(3) NOT NULL,
  ends_at DATETIME(3) NOT NULL,
  submitted_at DATETIME(3) NULL,
  answers MEDIUMTEXT NOT NULL,
  revision INT NOT NULL,
  program_score INT NOT NULL,
  KEY idx_edu_retake_student (cid,uid,id),
  CONSTRAINT fk_edu_retake_paper FOREIGN KEY (cid) REFERENCES edu_contest_paper(cid) ON DELETE CASCADE,
  CONSTRAINT fk_edu_retake_user FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS edu_paper_retired_submission (
  submit_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  retake_id BIGINT UNSIGNED NOT NULL,
  CONSTRAINT fk_edu_retired_retake FOREIGN KEY (retake_id) REFERENCES edu_paper_retake(id) ON DELETE CASCADE,
  CONSTRAINT fk_edu_retired_judge FOREIGN KEY (submit_id) REFERENCES judge(submit_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
