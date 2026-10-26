// Zone-less API dates are in HOJ's Asia/Shanghai time zone.
export function weeklyTimestamp (value) {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  if (!value) return 0
  const text=String(value).replace(' ','T')
  const time=Date.parse(/(?:Z|[+-]\d\d:\d\d)$/i.test(text) ? text : text + '+08:00')
  return Number.isFinite(time) ? time : 0
}
export function weeklyDate (value) { const time=weeklyTimestamp(value); return time ? new Date(time+8*3600000).toISOString().slice(0,10).replace(/-/g,'.') : '—' }
export function isCurrentWeek (training, now=Date.now()) {
  const local=new Date(now+8*3600000)
  const midnight=Date.UTC(local.getUTCFullYear(),local.getUTCMonth(),local.getUTCDate())-8*3600000
  const monday=midnight-((local.getUTCDay()+6)%7)*86400000
  const released=weeklyTimestamp(training.publishTime || training.gmtCreate)
  return released>=monday && released<monday+7*86400000 && released<=now
}
export function weeklyPercent (training) { return training.problemCount ? Math.min(100,Math.round((training.acCount || 0)/training.problemCount*100)) : 0 }
