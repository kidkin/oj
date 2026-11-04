export function chineseNumber (value) {
  const number = Number(value)
  if (!Number.isInteger(number) || number < 1 || number > 2147483647) return ''
  const digits = '零一二三四五六七八九'
  const part = n => {
    const units = ['', '十', '百', '千']; let result = ''; let zero = false
    for (let position = 3; position >= 0; position--) {
      const divisor = Math.pow(10, position); const digit = Math.floor(n / divisor) % 10
      if (digit) { if (zero) result += '零'; result += digits[digit] + units[position]; zero = false }
      else if (result && n % divisor) zero = true
    }
    return result
  }
  let result = ''; let gap = false
  for (const [divisor, unit] of [[100000000, '亿'], [10000, '万'], [1, '']]) {
    const group = Math.floor(number / divisor) % 10000
    if (group) { if (result && (gap || group < 1000)) result += '零'; result += part(group) + unit; gap = false }
    else if (result) gap = true
  }
  return result.replace(/^一十/, '十')
}
export function courseTitle (course) {
  if (!course) return ''
  const title = course.title || ''
  const number = chineseNumber(course.chapterNumber === undefined ? course.displayId : course.chapterNumber)
  const body = title.replace(/^第[零一二三四五六七八九十百千万亿两\d]+章[\s·:：、.-]*/, '')
  return number ? '第' + number + '章 · ' + body : title
}
