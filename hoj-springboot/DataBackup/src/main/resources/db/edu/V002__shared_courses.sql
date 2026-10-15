CREATE TABLE IF NOT EXISTS edu_course (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  title VARCHAR(128) NOT NULL,
  description TEXT NOT NULL,
  owner_uid VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  revision BIGINT UNSIGNED NOT NULL DEFAULT 1,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_edu_course_owner (owner_uid),
  KEY idx_edu_course_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_class_course (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  class_id BIGINT UNSIGNED NOT NULL,
  course_id BIGINT UNSIGNED NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_by VARCHAR(32) CHARACTER SET ${userCharset} COLLATE ${userCollation} NOT NULL,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_edu_class_course (class_id, course_id),
  KEY idx_edu_class_course_order (class_id, sort_order, id),
  KEY idx_edu_course_classes (course_id),
  CONSTRAINT fk_edu_class_course_class FOREIGN KEY (class_id) REFERENCES edu_class(id),
  CONSTRAINT fk_edu_class_course_course FOREIGN KEY (course_id) REFERENCES edu_course(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_course_problem (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  course_id BIGINT UNSIGNED NOT NULL,
  pid BIGINT UNSIGNED NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_edu_course_problem (course_id, pid),
  KEY idx_edu_course_problem_order (course_id, sort_order, id),
  KEY idx_edu_course_problem_pid (pid),
  CONSTRAINT fk_edu_course_problem_course FOREIGN KEY (course_id) REFERENCES edu_course(id),
  CONSTRAINT fk_edu_course_problem_problem FOREIGN KEY (pid) REFERENCES problem(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
