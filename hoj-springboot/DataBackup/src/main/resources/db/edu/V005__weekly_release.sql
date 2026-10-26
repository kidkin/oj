CREATE TABLE IF NOT EXISTS edu_weekly_release (
  training_id BIGINT UNSIGNED NOT NULL,
  published_at DATETIME(3) NOT NULL,
  PRIMARY KEY (training_id),
  KEY idx_weekly_release_time (published_at, training_id),
  CONSTRAINT fk_weekly_release_training FOREIGN KEY (training_id) REFERENCES training(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
