param([Parameter(Mandatory=$true)][ValidatePattern('^[a-zA-Z0-9_-]{1,40}$')][string]$TicketTypeId)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
# Single-instance local maintenance. Other deployed instances must be stopped separately.
& (Join-Path $PSScriptRoot 'stop.ps1')
. (Join-Path $project '../.runtime/env.ps1')
. (Join-Path $PSScriptRoot 'live-env.ps1')
Push-Location $project
try {
 & java.exe '-Dfile.encoding=UTF-8' -jar target/tickmeet-1.0.0.jar '--spring.profiles.active=live' '--tickmeet.maintenance=true' '--tickmeet.scheduling=false' '--spring.rabbitmq.listener.simple.auto-startup=false' '--tickmeet.seed=false' '--server.port=0' '--management.server.port=0' "--tickmeet.recover-type=$TicketTypeId"
 if($LASTEXITCODE -ne 0){throw 'Recovery failed; do not restart trading before checking the report.'}
}finally{Pop-Location}