$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
[IO.Directory]::CreateDirectory((Join-Path $project '.data'))|Out-Null
$path=Join-Path $project '.data/live.json'
if(Test-Path $path){Write-Host 'Live configuration already exists.';exit 0}
$existing=Get-Content (Join-Path $project '../.runtime/connections.json') -Raw|ConvertFrom-Json
$password=[Guid]::NewGuid().ToString('N')
$sql="CREATE DATABASE IF NOT EXISTS tickmeet CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE DATABASE IF NOT EXISTS tickmeet_test CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci; CREATE USER IF NOT EXISTS 'tickmeet'@'localhost' IDENTIFIED BY '$password'; ALTER USER 'tickmeet'@'localhost' IDENTIFIED BY '$password'; GRANT ALL ON tickmeet.* TO 'tickmeet'@'localhost'; GRANT ALL ON tickmeet_test.* TO 'tickmeet'@'localhost';"
$sql | wsl.exe -d Ubuntu -u root -- mysql
if($LASTEXITCODE -ne 0){throw 'Cannot initialize isolated TickMeet databases'}
@{dbUser='tickmeet';dbPassword=$password;mqUser=$existing.rabbitmqUser;mqPassword=$existing.rabbitmqPassword;callbackSecret=([Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N'))}|ConvertTo-Json|Set-Content -LiteralPath $path -Encoding utf8
Write-Host 'Created isolated tickmeet and tickmeet_test databases. Credentials stored in .data/live.json (gitignored).'
