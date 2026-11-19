CREATE TABLE IF NOT EXISTS edu_course_placement (
  course_id BIGINT UNSIGNED NOT NULL,
  category TINYINT NOT NULL,
  sort_order INT NOT NULL,
  PRIMARY KEY (course_id),
  KEY idx_course_placement_order (category,sort_order,course_id),
  CONSTRAINT fk_course_placement_course FOREIGN KEY (course_id) REFERENCES edu_course(id) ON DELETE CASCADE
) ENGINE=InnoDB;

INSERT INTO edu_course_placement(course_id,category,sort_order)
SELECT c.id,CASE
  WHEN c.title IN ('输入、输出与赋值语句','选择与分支','定数循环与迭代','不定循环','数据类型','嵌套循环与穷举法','一维数组','二维数组') THEN 1
  WHEN c.title IN ('自定义函数','字符串与综合运用','排序算法','结构体','SORT函数','贪心算法入门','线性数据结构','递归初步') THEN 2
  WHEN c.title IN ('子集与排列','归并排序与快速排序','深度优先搜索','广度优先搜索','二分搜索与二分答案','倍增与快速幂','最大子段和问题','尺取法','线性扫描与差分','贪心算法进阶') THEN 3
  ELSE 4 END,
  CASE c.title WHEN 'C++校内课程训练_1' THEN 1 WHEN 'C++校内课程训练_2' THEN 2
    WHEN 'Python校内课程训练_1' THEN 3 WHEN 'Python校内课程训练_2' THEN 4 WHEN 'Python校内课程训练_3' THEN 5
    ELSE COALESCE(d.display_id,c.id) END
FROM edu_course c LEFT JOIN edu_course_display d ON d.course_id=c.id
LEFT JOIN edu_course_placement placement ON placement.course_id=c.id WHERE placement.course_id IS NULL;

UPDATE edu_course SET status=2,revision=revision+1
WHERE status<>2 AND title IN ('C++课程（上）','C++课程（下）','Python课程（上）','Python课程（下）','风华高C++课后作业（基础）','风华高C++课后作业（提高）');
