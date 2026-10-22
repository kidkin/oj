CREATE TABLE IF NOT EXISTS edu_class_display (
  class_id BIGINT UNSIGNED NOT NULL,
  display_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (class_id),
  KEY idx_edu_class_display_order (display_id, class_id),
  CONSTRAINT fk_edu_class_display FOREIGN KEY (class_id) REFERENCES edu_class(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_course_display (
  course_id BIGINT UNSIGNED NOT NULL,
  display_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (course_id),
  KEY idx_edu_course_display_order (display_id, course_id),
  CONSTRAINT fk_edu_course_display FOREIGN KEY (course_id) REFERENCES edu_course(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS edu_course_problem_display (
  course_id BIGINT UNSIGNED NOT NULL,
  pid BIGINT UNSIGNED NOT NULL,
  display_id BIGINT UNSIGNED NOT NULL,
  PRIMARY KEY (course_id, pid),
  KEY idx_edu_course_problem_display_order (course_id, display_id, pid),
  CONSTRAINT fk_edu_course_problem_display FOREIGN KEY (course_id, pid) REFERENCES edu_course_problem(course_id, pid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO edu_class_display(class_id, display_id)
SELECT c.id, c.id FROM edu_class c LEFT JOIN edu_class_display d ON d.class_id=c.id WHERE d.class_id IS NULL;
INSERT INTO edu_course_display(course_id, display_id)
SELECT c.id, c.id FROM edu_course c LEFT JOIN edu_course_display d ON d.course_id=c.id WHERE d.course_id IS NULL;
INSERT INTO edu_course_problem_display(course_id, pid, display_id)
SELECT cp.course_id, cp.pid, cp.sort_order+1 FROM edu_course_problem cp
LEFT JOIN edu_course_problem_display d ON d.course_id=cp.course_id AND d.pid=cp.pid WHERE d.pid IS NULL;
