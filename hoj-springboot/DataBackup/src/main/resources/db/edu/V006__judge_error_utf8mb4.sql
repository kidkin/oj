-- Compiler diagnostics can quote four-byte characters from submitted source code.
-- Preserve the original diagnostic instead of failing while saving the verdict.
ALTER TABLE `judge`
  MODIFY COLUMN `error_message` MEDIUMTEXT
  CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci
  NULL COMMENT '错误提醒（编译错误，或者vj提醒）';
