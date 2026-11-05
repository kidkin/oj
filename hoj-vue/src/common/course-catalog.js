// Fetch bounded API batches; the card grid itself has no fixed page size.
export async function loadCourseCatalog (fetchPage, query, cancelled = () => false) {
  const limit = 50; const rows = new Map()
  for (let page = 1; page <= 100000; page++) {
    if (cancelled()) return []
    const data = await fetchPage({...query, page, limit})
    if (cancelled()) return []
    for (const row of data.records) if (row.status !== 2) rows.set(row.id, row)
    if (data.records.length === 0 || page * limit >= data.total) return Array.from(rows.values())
  }
  throw new Error('课程列表过大，请缩小搜索范围')
}
