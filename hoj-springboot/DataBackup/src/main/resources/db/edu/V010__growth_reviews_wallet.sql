CREATE TABLE IF NOT EXISTS edu_growth_invalid_submission (
  submit_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  invalidated_by VARCHAR(32) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  invalidated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  restored_by VARCHAR(32) DEFAULT NULL,
  restored_at DATETIME(3) DEFAULT NULL,
  CONSTRAINT fk_growth_review_submission FOREIGN KEY (submit_id) REFERENCES judge(submit_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_growth_wallet_debit (
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  operation_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  units BIGINT UNSIGNED NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (uid, operation_id),
  CONSTRAINT fk_growth_debit_user FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
