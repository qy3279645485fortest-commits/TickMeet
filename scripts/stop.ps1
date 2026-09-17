$project=Split-Path $PSScriptRoot -Parent
$marker=Join-Path $project '.data/app-process.json'
if(Test-Path $marker){$saved=Get-Content $marker -Raw|ConvertFrom-Json;$process=Get-Process -Id $saved.id -ErrorAction SilentlyContinue;if($process -and $process.ProcessName -eq 'java' -and $process.StartTime.ToUniversalTime().Ticks -eq ([DateTime]$saved.started).ToUniversalTime().Ticks){Stop-Process -Id $process.Id; if(!$process.WaitForExit(10000)){throw 'TickMeet did not stop within 10 seconds'}};Remove-Item -LiteralPath $marker}
