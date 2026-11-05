CREATE TABLE IF NOT EXISTS edu_growth_class (
  class_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  CONSTRAINT fk_growth_class FOREIGN KEY (class_id) REFERENCES edu_class(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_growth_account (
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL PRIMARY KEY,
  mode ENUM('trial', 'formal') NOT NULL,
  CONSTRAINT fk_growth_account FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
