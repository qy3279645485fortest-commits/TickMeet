$project=Split-Path $PSScriptRoot -Parent
$c=Get-Content (Join-Path $project '.data/live.json') -Raw|ConvertFrom-Json
$env:TICKMEET_DB_USER=$c.dbUser
$env:TICKMEET_DB_PASSWORD=$c.dbPassword
$env:TICKMEET_MQ_USER=$c.mqUser
$env:TICKMEET_MQ_PASSWORD=$c.mqPassword
$env:TICKMEET_CALLBACK_SECRET=$c.callbackSecret
