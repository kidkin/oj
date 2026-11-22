CREATE TABLE IF NOT EXISTS edu_roster_name (
  uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL PRIMARY KEY,
  public_name VARCHAR(100) NOT NULL,
  KEY idx_edu_roster_name (public_name),
  CONSTRAINT fk_edu_roster_name_user FOREIGN KEY (uid) REFERENCES user_info(uuid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE OR REPLACE SQL SECURITY INVOKER VIEW edu_effective_display_name AS
  SELECT uid,public_name FROM edu_display_name
  UNION ALL
  SELECT r.uid,r.public_name FROM edu_roster_name r
  WHERE NOT EXISTS (SELECT 1 FROM edu_display_name n WHERE n.uid=r.uid);
