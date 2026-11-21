INSERT INTO edu_review_rules(revision, words, editor)
SELECT r.revision + 1, r.words, 'builtin-a7d06bb1'
FROM edu_review_rules r
WHERE NOT EXISTS (SELECT 1 FROM edu_review_rules applied WHERE applied.editor='builtin-a7d06bb1')
ORDER BY r.revision DESC LIMIT 1;
