export const PROGRESS_STATES = {
  CORRECT: {label: '正确', background: '#42b883', color: '#ffffff'},
  WRONG: {label: '错误', background: '#ef8c29', color: '#ffffff'},
  NOT_SUBMITTED: {label: '未提交', background: '#e53935', color: '#ffffff'},
  PARTIAL: {label: '部分正确', background: '#ffec99', color: '#201a0b'},
  PENDING: {label: '判题中', background: '#909399', color: '#ffffff'}
}

// Keep both the DOM preview and exported canvases bounded without omitting students.
export function progressPages (report) {
  if (!report) return []
  const result = []; const rows = report.students.length; const columns = report.problems.length
  for (let col = 0; col < columns; col += 12) {
    for (let row = 0; row < Math.max(1, rows); row += 80) {
      result.push({rowStart: row, rowEnd: Math.min(rows, row + 80), colStart: col, colEnd: Math.min(columns, col + 12)})
    }
  }
  return result
}

export function progressTime (timestamp) {
  return new Date(timestamp).toLocaleString('zh-CN', {timeZone: 'Asia/Shanghai', hour12: false})
}

function lines (ctx, value, width) {
  const result = []; let line = ''
  for (const char of Array.from(String(value == null ? '' : value))) {
    if (char === '\n' || (line && ctx.measureText(line + char).width > width)) { result.push(line); line = char === '\n' ? '' : char } else line += char
  }
  result.push(line); return result
}

function centered (ctx, text, x, y, lineHeight) {
  text.forEach((line, index) => ctx.fillText(line, x, y + (index - (text.length - 1) / 2) * lineHeight))
}

export async function renderProgressImage (report, page, pageIndex, pageCount) {
  const canvas = document.createElement('canvas'); let ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('浏览器无法生成图片，请更换浏览器后重试')
  const font = size => `${size}px "Microsoft YaHei", "PingFang SC", sans-serif`
  const nameWidth = 220; const problemWidth = 200
  const problems = report.problems.slice(page.colStart, page.colEnd); const students = report.students.slice(page.rowStart, page.rowEnd)
  const width = nameWidth + problems.length * problemWidth
  ctx.font = font(20)
  const headings = problems.map(p => { ctx.font=font(20); const title=lines(ctx,p.title,problemWidth-20); ctx.font=font(12); return {title,id:lines(ctx,p.problemId,problemWidth-20)} })
  ctx.font=font(20)
  const headerHeight = Math.max(92, ...headings.map(p => p.title.length * 25 + p.id.length * 17 + 24))
  const names = students.map(student => lines(ctx, student.name, nameWidth - 24))
  const rowHeights = names.map(name => Math.max(48, name.length * 24 + 14))
  ctx.font = font(22)
  const caption = lines(ctx, report.className + ' · 学习情况', width - 32)
  const captionHeight = caption.length * 28 + 32
  ctx.font = font(13)
  const footer = lines(ctx, `生成：${progressTime(report.generatedAt)} · 第${pageIndex + 1}/${pageCount}张 · 学生${page.rowStart + (students.length ? 1 : 0)}—${page.rowEnd}（共${report.studentCount}人） · 题目${page.colStart + 1}—${page.colEnd}`, width - 24)
  const height = captionHeight + headerHeight + (students.length ? rowHeights.reduce((a,b) => a+b,0) : 56) + footer.length * 19 + 22
  const scale = Math.min(2, 16000 / width, 16000 / height, Math.sqrt(16000000 / (width * height)))
  canvas.width = Math.ceil(width * scale); canvas.height = Math.ceil(height * scale)
  ctx = canvas.getContext('2d'); ctx.scale(scale, scale); ctx.fillStyle = '#ffffff'; ctx.fillRect(0,0,width,height)
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillStyle = '#202020'; ctx.font = font(22)
  centered(ctx, caption, width/2, captionHeight/2, 28)
  const top = captionHeight; const bottom = top + headerHeight + (students.length ? rowHeights.reduce((a,b) => a+b,0) : 56)
  ctx.font = font(22); ctx.fillText('姓名/题目', nameWidth/2, top+headerHeight/2)
  headings.forEach((heading,index) => {
    const x = nameWidth + problemWidth * (index+0.5); const blockHeight = heading.title.length*25+heading.id.length*17+8; const start = top+(headerHeight-blockHeight)/2
    ctx.font = font(20); ctx.fillStyle = '#202020'; heading.title.forEach((line,i) => ctx.fillText(line,x,start+12+i*25))
    ctx.font = font(12); ctx.fillStyle = '#606266'; heading.id.forEach((line,i) => ctx.fillText(line,x,start+heading.title.length*25+12+i*17))
  })
  let y = top+headerHeight
  students.forEach((student,index) => {
    const rowHeight = rowHeights[index]; ctx.font = font(20); ctx.fillStyle = '#202020'; centered(ctx,names[index],nameWidth/2,y+rowHeight/2,24)
    for (let col=page.colStart; col<page.colEnd; col++) {
      const state = PROGRESS_STATES[student.results[col]] || PROGRESS_STATES.PENDING
      const x = nameWidth+(col-page.colStart+0.5)*problemWidth; const badgeWidth = ctx.measureText(state.label).width+24; const badgeHeight = 34
      ctx.fillStyle=state.background; ctx.fillRect(x-badgeWidth/2,y+(rowHeight-badgeHeight)/2,badgeWidth,badgeHeight)
      ctx.fillStyle=state.color; ctx.fillText(state.label,x,y+rowHeight/2)
    }
    y+=rowHeight
  })
  if (!students.length) { ctx.font=font(18); ctx.fillStyle='#606266'; ctx.fillText('本班暂无有效学生',width/2,y+28) }
  ctx.strokeStyle='#202020'; ctx.lineWidth=1.5; ctx.beginPath(); ctx.rect(1,top,width-2,bottom-top)
  for (let x=nameWidth; x<width; x+=problemWidth) { ctx.moveTo(x,top); ctx.lineTo(x,bottom) }
  y=top+headerHeight; ctx.moveTo(0,y); ctx.lineTo(width,y)
  rowHeights.forEach(rowHeight => { y+=rowHeight; ctx.moveTo(0,y); ctx.lineTo(width,y) }); ctx.stroke()
  ctx.font=font(13); ctx.fillStyle='#606266'; centered(ctx,footer,width/2,bottom+(height-bottom)/2,19)
  const blob = await new Promise((resolve,reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('图片生成失败，请重试')),'image/png'))
  const result = {blob,width,height,pixelWidth:canvas.width,pixelHeight:canvas.height}
  canvas.width=1; canvas.height=1; return result
}
