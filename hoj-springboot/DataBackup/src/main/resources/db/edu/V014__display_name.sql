CREATE TABLE IF NOT EXISTS edu_display_name (
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  public_name VARCHAR(24) NOT NULL,
  PRIMARY KEY (uid),
  UNIQUE KEY uk_edu_public_name (public_name),
  FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
