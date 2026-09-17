param([ValidateSet('demo','live')][string]$Profile='demo',[switch]$Rebuild)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
. (Join-Path $project '../.runtime/env.ps1')
if($Profile -eq 'live'){. (Join-Path $PSScriptRoot 'live-env.ps1')}
Push-Location $project
try {
 [IO.Directory]::CreateDirectory((Join-Path $project '.data'))|Out-Null
 $marker=Join-Path $project '.data/app-process.json'
 if(Test-Path $marker){
  $saved=Get-Content $marker -Raw|ConvertFrom-Json
  $old=Get-Process -Id $saved.id -ErrorAction SilentlyContinue
  if($old -and $old.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$saved.started).ToUniversalTime().Ticks){
   if($Rebuild){& (Join-Path $PSScriptRoot 'stop.ps1')}
   else{Write-Host 'TickMeet already running: http://127.0.0.1:8088 ; use -Rebuild after source changes.';return}
  }
 }
 $jar=Join-Path $project 'target/tickmeet-1.0.0.jar'
 $latest=Get-ChildItem -LiteralPath 'src/main' -File -Recurse | Sort-Object LastWriteTime -Descending | Select-Object -First 1
 $needsBuild=$Rebuild -or !(Test-Path $jar)
 if(!$needsBuild){$needsBuild=$latest.LastWriteTime -gt (Get-Item $jar).LastWriteTime -or (Get-Item 'pom.xml').LastWriteTime -gt (Get-Item $jar).LastWriteTime}
 if($needsBuild){& mvn.cmd -B -ntp '-Dmaven.repo.local=.maven' '-DskipTests' package;if($LASTEXITCODE -ne 0){throw 'Build failed'}}
 $process=Start-Process -FilePath (Join-Path $env:JAVA_HOME 'bin/java.exe') -ArgumentList '-Dfile.encoding=UTF-8','-jar','target/tickmeet-1.0.0.jar',"--spring.profiles.active=$Profile" -WorkingDirectory $project -WindowStyle Hidden -RedirectStandardOutput (Join-Path $project '.data/app.log') -RedirectStandardError (Join-Path $project '.data/app-error.log') -PassThru
 @{id=$process.Id;started=$process.StartTime.ToUniversalTime().ToString('o');profile=$Profile}|ConvertTo-Json|Set-Content $marker
 for($attempt=0;$attempt -lt 30;$attempt++){
  try{$response=Invoke-WebRequest -Uri 'http://127.0.0.1:8088/api/event-categories' -UseBasicParsing -TimeoutSec 1;if($response.StatusCode -eq 200){Write-Host 'TickMeet ready: http://127.0.0.1:8088';return}}catch{}
  if($process.HasExited){Get-Content '.data/app.log' -Tail 20;throw 'TickMeet exited during startup'}
  Start-Sleep -Seconds 1
 }
 throw 'Startup not ready after 30s; check .data/app.log'
}finally{Pop-Location}
