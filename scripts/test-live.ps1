param([string]$Module='LiveModuleTest,LiveRecoveryTest')
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
. (Join-Path $project '../.runtime/env.ps1')
. (Join-Path $PSScriptRoot 'live-env.ps1')
$previousUrl=$env:TICKMEET_DB_URL
try {
 $env:TICKMEET_LIVE_TEST='true'
 $env:TICKMEET_DB_URL='jdbc:mysql://127.0.0.1:3306/tickmeet_test?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC'
 Push-Location $project
 try { & mvn.cmd -B -ntp '-Dmaven.repo.local=.maven' "-Dtest=$Module" test; if($LASTEXITCODE -ne 0){throw 'Live tests failed'} } finally {Pop-Location}
}finally{$env:TICKMEET_DB_URL=$previousUrl;Remove-Item Env:TICKMEET_LIVE_TEST -ErrorAction SilentlyContinue}
