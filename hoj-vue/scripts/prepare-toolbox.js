// Download the user-supplied installer as data only; never execute it.
// The release image carries it so downloads survive a server migration.
const fs = require('fs')
const path = require('path')
const os = require('os')
const http = require('http')
const https = require('https')
const crypto = require('crypto')
const { pipeline } = require('stream')
const { promisify } = require('util')
const copy = promisify(pipeline)
const asset = require('./toolbox-asset.json')
const cache = path.join(os.homedir(), '.cache/hoj-toolbox', asset.sha256 + '.exe')
const output = path.resolve(__dirname, '../public/downloads/Dev-Cpp.exe')

async function valid (file) {
  try {
    if ((await fs.promises.stat(file)).size !== asset.bytes) return false
    const hash = crypto.createHash('sha256')
    for await (const chunk of fs.createReadStream(file)) hash.update(chunk)
    return hash.digest('hex') === asset.sha256
  } catch (error) { if (error.code === 'ENOENT') return false; throw error }
}

function download (url, file, redirects = 0) {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url)
    if (!['http:', 'https:'].includes(parsed.protocol) || redirects > 3) return reject(new Error('Invalid installer redirect'))
    const request = (parsed.protocol === 'https:' ? https : http).get(parsed, response => {
      if ([301, 302, 307, 308].includes(response.statusCode)) {
        response.resume()
        return resolve(download(new URL(response.headers.location, parsed).href, file, redirects + 1))
      }
      if (response.statusCode !== 200) { response.resume(); return reject(new Error('Installer download failed: ' + response.statusCode)) }
      let received = 0
      response.on('data', chunk => { received += chunk.length; if (received > asset.bytes) response.destroy(new Error('Installer exceeds recorded size')) })
      copy(response, fs.createWriteStream(file, { flags: 'wx' })).then(resolve, reject)
    })
    request.setTimeout(120000, () => request.destroy(new Error('Installer download timed out')))
    request.on('error', reject)
  })
}

async function main () {
  if (await valid(output)) return
  await fs.promises.mkdir(path.dirname(cache), { recursive: true })
  if (!await valid(cache)) {
    const temporary = cache + '.' + process.pid + '.partial'
    try {
      await download(asset.source, temporary)
      if (!await valid(temporary)) throw new Error('Installer checksum mismatch')
      await fs.promises.rename(temporary, cache)
    } finally { await fs.promises.unlink(temporary).catch(error => { if (error.code !== 'ENOENT') throw error }) }
  }
  await fs.promises.mkdir(path.dirname(output), { recursive: true })
  await fs.promises.copyFile(cache, output)
  console.log('Dev-C++ download asset verified and included in the frontend.')
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
