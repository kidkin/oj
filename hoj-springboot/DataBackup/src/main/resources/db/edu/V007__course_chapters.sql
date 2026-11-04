CREATE TABLE IF NOT EXISTS edu_course_chapter (
  course_id BIGINT UNSIGNED NOT NULL,
  chapter_number INT NOT NULL,
  PRIMARY KEY (course_id),
  CONSTRAINT fk_course_chapter_course FOREIGN KEY (course_id) REFERENCES edu_course(id) ON DELETE CASCADE
) ENGINE=InnoDB;

INSERT INTO edu_course_chapter(course_id,chapter_number)
SELECT c.id,COALESCE(d.display_id,c.id)
FROM edu_course c LEFT JOIN edu_course_display d ON d.course_id=c.id
LEFT JOIN edu_course_chapter chapters ON chapters.course_id=c.id
WHERE chapters.course_id IS NULL;
