[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9.-]*(?::[0-9]+)?/[a-z0-9][a-z0-9_./-]*$')]
    [string]$RegistryNamespace,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$')]
    [string]$Version,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$RegistryNamespace = $RegistryNamespace.TrimEnd('/')
$dockerCommand = Get-Command docker.exe -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $dockerExe = $dockerCommand.Source
} else {
    $dockerExe = Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin\docker.exe'
}
if (-not (Test-Path -LiteralPath $dockerExe)) { throw 'Docker CLI was not found. Start Docker Desktop first.' }
$gitCommand = Get-Command git.exe -ErrorAction SilentlyContinue
if (-not $gitCommand) { throw 'Git was not found. Open a new terminal after installing Git.' }
$gitExe = $gitCommand.Source

function Invoke-GitRead {
    param([string[]]$GitArguments)
    $result = & $gitExe -C $repoRoot @GitArguments
    if ($LASTEXITCODE -ne 0) { throw 'Could not verify application source in Git.' }
    return (($result | Out-String).Trim())
}

$applications = @(
    @{ Local = 'hoj-local/backend:dev'; Remote = 'hoj-backend'; Source = 'hoj-springboot' },
    @{ Local = 'hoj-local/frontend:dev'; Remote = 'hoj-frontend'; Source = 'hoj-vue' },
    @{ Local = 'hoj-local/judgeserver:dev'; Remote = 'hoj-judgeserver'; Source = 'hoj-springboot' }
)
$releaseImages = @()
foreach ($app in $applications) {
    $dirty = Invoke-GitRead -GitArguments @('status', '--porcelain', '--untracked-files=normal', '--', $app.Source)
    if ($dirty) { throw ('Commit application changes before publishing: ' + $app.Source) }
    $inspection = & $dockerExe image inspect $app.Local
    if ($LASTEXITCODE -ne 0) { throw ('Build the local application image first: ' + $app.Local) }
    $image = @((($inspection | Out-String) | ConvertFrom-Json))[0]
    if ($image.Os -ne 'linux' -or $image.Architecture -ne 'amd64') {
        throw ('The current ECS installer requires a linux/amd64 application image: ' + $app.Local)
    }
    $revision = $image.Config.Labels.'org.opencontainers.image.revision'
    if ($image.Config.Labels.'hoj.build' -ne 'source' -or $revision -notmatch '^[0-9a-f]{7,40}$') {
        throw ('The local image does not identify a committed source build: ' + $app.Local)
    }
    $imageTree = Invoke-GitRead -GitArguments @('rev-parse', ($revision + ':' + $app.Source))
    $currentTree = Invoke-GitRead -GitArguments @('rev-parse', ('HEAD:' + $app.Source))
    if ($imageTree -ne $currentTree) { throw ('Application code changed after this image was built. Rebuild: ' + $app.Local) }
    $releaseImages += [pscustomobject]@{
        LocalImage = $app.Local
        LocalImageId = $image.Id
        SourceRevision = $revision
        ApplicationTree = $imageTree
        PublishedImage = ($RegistryNamespace + '/' + $app.Remote + ':' + $Version)
    }
}

$logRoot = Join-Path $repoRoot 'logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$reportName = if ($PlanOnly) { 'release-plan-' + $Version + '.json' } else { 'release-images-' + $Version + '.json' }
$reportPath = Join-Path $logRoot $reportName
if (-not $PlanOnly -and (Test-Path -LiteralPath $reportPath)) { throw 'A release record already exists for this version. Review it and use a new version for changes.' }

if (-not $PlanOnly) {
    foreach ($entry in $releaseImages) {
        & $dockerExe tag $entry.LocalImage $entry.PublishedImage
        if ($LASTEXITCODE -ne 0) { throw ('Image tagging failed: ' + $entry.LocalImage) }
        & $dockerExe push --platform linux/amd64 $entry.PublishedImage
        if ($LASTEXITCODE -ne 0) { throw ('Image push failed. Check registry login and network access, then retry: ' + $entry.PublishedImage) }
    }
}
$report = [pscustomobject]@{
    Version = $Version
    Status = $(if ($PlanOnly) { 'planned' } else { 'published' })
    Images = $releaseImages
}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $reportPath -Encoding UTF8
$releaseImages | Format-Table PublishedImage, SourceRevision -AutoSize
Write-Output ('Release record: ' + $reportPath)
if ($PlanOnly) { Write-Output 'Plan only: no images were tagged or pushed.' }
