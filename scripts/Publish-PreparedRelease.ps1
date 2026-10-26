[CmdletBinding()]
param(
    [string]$PreparationDirectory,
    [string]$RegistryNamespace = 'crpi-06ni3fl9fozs6y04.cn-shanghai.personal.cr.aliyuncs.com/kidkin-oj',
    [string]$CranePath,
    [string]$GitProxy = 'http://127.0.0.1:10808',
    [switch]$Publish
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $PreparationDirectory) { $PreparationDirectory = Join-Path $repoRoot 'backups\ready-v0.3.1' }
$PreparationDirectory = (Resolve-Path -LiteralPath $PreparationDirectory).Path
$request = Get-Content -LiteralPath (Join-Path $PreparationDirectory 'migration.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$version = $request.Version
if ($version -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Invalid prepared release version.' }
$bundle = Join-Path $PreparationDirectory ('hoj-migration-' + $version + '.zip')
$expectedHash = ((Get-Content -LiteralPath ($bundle + '.sha256') -Raw).Trim() -split '\s+')[0]
if ((Get-FileHash -LiteralPath $bundle -Algorithm SHA256).Hash.ToLowerInvariant() -cne $expectedHash) {
    throw 'Migration ZIP checksum failed. Do not publish or upload it.'
}
$gitExe = (Get-Command git.exe -ErrorAction Stop).Source
$dockerExe = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
if (-not (Test-Path -LiteralPath $dockerExe)) { $dockerExe = (Get-Command docker.exe -ErrorAction Stop).Source }
$env:PATH = (Split-Path -Parent $dockerExe) + ';' + $env:PATH
if (-not $CranePath) {
    $CranePath = Join-Path (Split-Path -Parent $repoRoot) 'downloads\crane-v0.22.1\crane.exe'
}
if (-not (Test-Path -LiteralPath $CranePath -PathType Leaf)) { throw 'Set -CranePath to your verified crane.exe.' }

function Read-Git {
    param([string[]]$Arguments)
    $result = & $gitExe -C $repoRoot @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'Git verification failed.' }
    return (($result | Out-String).Trim())
}

$origin = Read-Git -Arguments @('remote', 'get-url', 'origin')
if ($origin -notmatch '^https://github\.com/kidkin/oj(?:\.git)?$') {
    throw 'Unexpected Git remote. Review the destination before publishing.'
}
$branch = Read-Git -Arguments @('symbolic-ref', '--short', 'HEAD')
$manifestRelative = 'releases/' + $version + '.json'
$changed = @(& $gitExe -C $repoRoot diff --name-only HEAD) + @(& $gitExe -C $repoRoot ls-files --others --exclude-standard)
if (@($changed | Where-Object { $_ -and $_ -ne $manifestRelative }).Count) {
    throw 'Commit preparation scripts and application changes before publishing.'
}
foreach ($service in @('hoj-backend', 'hoj-frontend', 'hoj-judgeserver')) {
    $shortName = $service.Substring(4)
    $inspection = & $dockerExe image inspect ('hoj-local/' + $shortName + ':dev')
    if ($LASTEXITCODE -ne 0) { throw ('Prepared local image is unavailable: ' + $service) }
    $image = @(($inspection | Out-String | ConvertFrom-Json))[0]
    if ($image.Id -cne $request.ApplicationImages.$service.Id) {
        throw ('Local image changed after this data package was prepared: ' + $service + '. Prepare a fresh package.')
    }
}
$publication = @{ RegistryNamespace = $RegistryNamespace; Version = $version; CranePath = $CranePath }
if (-not $Publish) {
    & (Join-Path $PSScriptRoot 'Publish-Images.ps1') @publication -PlanOnly
    Write-Output ('PRIVATE MIGRATION ZIP READY: ' + $bundle)
    Write-Output 'Preview only. Use -Publish when ready to upload the three images and Git release tag.'
    exit 0
}

$manifestPath = Join-Path $repoRoot $manifestRelative
if (-not (Test-Path -LiteralPath $manifestPath)) {
    & (Join-Path $PSScriptRoot 'Publish-Images.ps1') @publication
    if (-not (Test-Path -LiteralPath $manifestPath)) { throw 'Image publication did not produce a verified release manifest.' }
} else {
    # A network failure during Git push must not require republishing images.
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($service in @('hoj-backend', 'hoj-frontend', 'hoj-judgeserver')) {
        $record = $manifest.Images.$service
        if ($record.SourceRevision -cne $request.ApplicationImages.$service.SourceRevision -or
            $record.Reference -cne ($RegistryNamespace + '/' + $service + ':' + $version)) {
            throw 'Existing release manifest differs from this preparation. Use a new version.'
        }
        $actualDigest = & $CranePath digest $record.Reference
        if ($LASTEXITCODE -ne 0 -or $actualDigest -cne $record.Digest) { throw 'Existing published image digest check failed.' }
    }
}
& $gitExe -C $repoRoot add -- $manifestRelative
if ($LASTEXITCODE -ne 0) { throw 'Could not stage the public release manifest.' }
& $gitExe -C $repoRoot diff --cached --quiet
if ($LASTEXITCODE -eq 1) {
    & $gitExe -C $repoRoot commit -m ('release: publish ' + $version)
    if ($LASTEXITCODE -ne 0) { throw 'Could not commit the release manifest.' }
} elseif ($LASTEXITCODE -ne 0) { throw 'Could not inspect the release commit.' }
$commit = Read-Git -Arguments @('rev-parse', 'HEAD')
& $gitExe -C $repoRoot show-ref --verify --quiet ('refs/tags/' + $version)
if ($LASTEXITCODE -eq 0) {
    $tagged = Read-Git -Arguments @('rev-parse', ($version + '^{commit}'))
    if ($tagged -cne $commit) { throw 'An existing version tag points to another commit. Do not overwrite it.' }
} else {
    & $gitExe -C $repoRoot tag -a $version -m ('HOJ ' + $version)
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the release tag.' }
}
$gitOptions = @('-c', 'http.version=HTTP/1.1')
if ($GitProxy) { $gitOptions += @('-c', ('http.proxy=' + $GitProxy)) }
& $gitExe -C $repoRoot @gitOptions push --atomic origin ('HEAD:refs/heads/' + $branch) ('refs/tags/' + $version)
if ($LASTEXITCODE -ne 0) { throw 'Images are published; Git push failed. Fix connectivity and rerun this same command.' }
Write-Output ('RELEASE PUBLISHED: ' + $version)
Write-Output ('COMMIT: ' + $commit)
Write-Output ('Upload this PRIVATE ZIP through Aliyun Workbench to /root/hoj-incoming/: ' + $bundle)
Write-Output 'Then follow MIGRATION.md in the repository. No ECS services have been changed by this publisher.'
