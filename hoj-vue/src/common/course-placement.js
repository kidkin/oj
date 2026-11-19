export const COURSE_CATEGORIES = [
  {id:1, title:'流程控制与循环迭代'},
  {id:2, title:'过程抽象与泛型编程'},
  {id:3, title:'算法设计与分析'},
  {id:4, title:'其他课程（校本课程）'}
]
export function courseTrack (row) { return Number(row.category) === 4 ? 'school' : 'training' }
export function compareCourses (a, b) { return Number(a.category) - Number(b.category) || Number(a.categoryOrder) - Number(b.categoryOrder) || Number(a.id) - Number(b.id) }
export function availableCourseTracks (records) { return ['training', 'school'].filter(track => records.some(row => courseTrack(row) === track)) }
