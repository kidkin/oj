param(
    [string]$BaseUrl = 'http://127.0.0.1:18081',
    [string]$Username = 'root',
    [string]$Password = 'hoj123456'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskProblemId = 'SOURCE_AB_BASELINE'
$taskResults = @()
$taskHeaders = @{}
$taskSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Invoke-HojApi {
    param([string]$Path, [string]$Method = 'GET', $Data = $null)
    $taskRequestHeaders = @{ 'Url-Type' = $(if ($Path.StartsWith('/api/admin/')) { 'admin' } else { 'general' }) }
    if ($script:taskHeaders.Authorization) { $taskRequestHeaders.Authorization = $script:taskHeaders.Authorization }
    $taskRequest = @{
        Uri = $script:BaseUrl.TrimEnd('/') + $Path
        Method = $Method
        Headers = $taskRequestHeaders
        WebSession = $script:taskSession
        UseBasicParsing = $true
        TimeoutSec = 30
    }
    if ($null -ne $Data) {
        $taskRequest.Body = [Text.Encoding]::UTF8.GetBytes(($Data | ConvertTo-Json -Depth 15 -Compress))
        $taskRequest.ContentType = 'application/json; charset=utf-8'
    }
    $taskResponse = Invoke-WebRequest @taskRequest
    $taskContent = [Text.Encoding]::UTF8.GetString($taskResponse.RawContentStream.ToArray()) | ConvertFrom-Json
    if ($taskContent.status -ne 200) { throw ('HOJ request failed: ' + $Path + ': ' + $taskContent.msg) }
    if ($taskResponse.Headers['Authorization']) {
        $script:taskHeaders.Authorization = @($taskResponse.Headers['Authorization'])[0]
    }
    return $taskContent.data
}

$taskWeb = Invoke-WebRequest -Uri ($BaseUrl.TrimEnd('/') + '/') -UseBasicParsing -TimeoutSec 30
if ($taskWeb.StatusCode -ne 200) { throw 'HOJ web page is not available.' }
$taskAccount = Invoke-HojApi -Path '/api/login' -Method POST -Data @{ username=$Username; password=$Password }
if (-not $taskHeaders.Authorization) { throw 'Login did not return an authentication token.' }
Write-Output 'Website and administrator login verified.'

$taskLanguages = @(Invoke-HojApi -Path '/api/languages?all=true')
$taskLanguage = $taskLanguages | Where-Object { $_.name -match '^C\+\+' } | Sort-Object id | Select-Object -First 1
if (-not $taskLanguage) { throw 'No supported C++ language was returned.' }
$taskExisting = Invoke-HojApi -Path ('/api/admin/problem/get-problem-list?limit=10&currentPage=1&keyword=' + $taskProblemId)
$taskProblem = $taskExisting.records | Where-Object { $_.problemId -eq $taskProblemId } | Select-Object -First 1
if (-not $taskProblem) {
    $taskProblemBody = @{
        problemId=$taskProblemId; title='Local acceptance - A+B'; author=$Username
        description='Read two integers a and b, and print their sum.'
        input='Two integers a and b.'; output='Print a+b followed by a newline.'
        timeLimit=1000; memoryLimit=128; stackLimit=128; difficulty=0; auth=1
        codeShare=$true; examples=''; isRemote=$false; isGroup=$false; type=0
        hint='Local installation acceptance fixture.'; source='Local acceptance'
        isRemoveEndBlank=$false; openCaseResult=$true
        judgeMode='default'; judgeCaseMode='default'; isFileIO=$false
        userExtraFile=''; judgeExtraFile=''
    }
    $taskSamples = 1..3 | ForEach-Object {
        @{
            input=[IO.File]::ReadAllText((Join-Path $PSScriptRoot ('acceptance\' + $_ + '.in')))
            output=[IO.File]::ReadAllText((Join-Path $PSScriptRoot ('acceptance\' + $_ + '.out')))
            score=0; groupNum=1; status=0
        }
    }
    $taskCreate = @{
        problem=$taskProblemBody; languages=@($taskLanguage); tags=@(); codeTemplates=@()
        samples=@($taskSamples); isUploadTestCase=$false; judgeMode='default'
        changeModeCode=$false; changeJudgeCaseMode=$false
    }
    Invoke-HojApi -Path '/api/admin/problem' -Method POST -Data $taskCreate | Out-Null
    Write-Output ('Created acceptance problem: ' + $taskProblemId)
}

foreach ($taskCase in @(@{Name='AC';Expected=0}, @{Name='WA';Expected=-1})) {
    if ($taskResults.Count -gt 0) {
        Write-Output 'Waiting for the original HOJ submission rate limit before the next program.'
        Start-Sleep -Seconds 12
    }
    $taskSourceCode = [IO.File]::ReadAllText((Join-Path $PSScriptRoot ('acceptance\' + $taskCase.Name + '.cpp')))
    $taskSubmit = Invoke-HojApi -Path '/api/submit-problem-judge' -Method POST -Data @{
        pid=$taskProblemId; language=$taskLanguage.name; code=$taskSourceCode
        cid=0; tid=$null; gid=$null; isRemote=$false
    }
    $taskSubmitId = $taskSubmit.submitId
    if (-not $taskSubmitId) { throw 'The judge submission did not return its submission ID.' }
    $taskDeadline = (Get-Date).AddMinutes(3)
    do {
        Start-Sleep -Seconds 2
        $taskDetail = Invoke-HojApi -Path ('/api/get-submission-detail?submitId=' + $taskSubmitId)
        $taskSubmission = $taskDetail.submission
        if ($null -ne $taskSubmission.status -and $taskSubmission.status -notin @(5,6,7,9,15)) { break }
    } while ((Get-Date) -lt $taskDeadline)
    $taskResult = [pscustomobject]@{
        Expected=$taskCase.Name; ExpectedStatus=$taskCase.Expected
        ActualStatus=$taskSubmission.status; Passed=($taskSubmission.status -eq $taskCase.Expected)
        SubmissionId=$taskSubmitId; Language=$taskLanguage.name
        Time=$taskSubmission.time; Memory=$taskSubmission.memory; Error=$taskSubmission.errorMessage
    }
    $taskResults += $taskResult
    [pscustomobject]@{
        Website=$BaseUrl; ProblemId=$taskProblemId; LoginVerified=$true
        Results=$taskResults; VerifiedAt=(Get-Date).ToString('o')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRoot 'logs\acceptance.json') -Encoding UTF8
    $taskResult | Format-List | Out-String | Write-Output
    if (-not $taskResult.Passed) { throw ('Acceptance failed for ' + $taskCase.Name + '. See logs/acceptance.json.') }
}
Write-Output 'PASS: Source-built HOJ web, login, AC and WA acceptance checks completed.'
