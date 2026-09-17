param([string]$Module='*Test')
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '../../.runtime/env.ps1')
Push-Location (Join-Path $PSScriptRoot '..')
try { & mvn.cmd "-Dmaven.repo.local=$PWD/.maven" "-Dtest=$Module" test; if($LASTEXITCODE -ne 0){throw 'Tests failed'} } finally {Pop-Location}

