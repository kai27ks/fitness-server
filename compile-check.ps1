$jdk = 'C:\Users\user\.jdks\ms-21.0.12.1\bin\javac.exe'
$repo = 'C:\Users\user\IdeaProjects\jianshenfang'
$m2 = 'C:\Users\user\.m2\repository'
$out = Join-Path $env:TEMP 'jsf-compile'
New-Item -ItemType Directory -Force -Path $out | Out-Null
$all = Get-ChildItem $m2 -Recurse -Filter '*.jar' | Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' }
$cp = ($all | ForEach-Object { $_.FullName.Replace('\','/') }) -join ';'
$lombok = ($all | Where-Object { $_.Name -like 'lombok-*.jar' } | Select-Object -First 1).FullName.Replace('\','/')
$src = Get-ChildItem (Join-Path $repo 'src\main\java') -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
$argFile = Join-Path $env:TEMP 'jsf-javac-args.txt'
$line = '-encoding UTF-8 -implicit:none -proc:full -processorpath "' + $lombok + '" -cp "' + $cp + '" -d "' + $out.Replace('\','/') + '" ' + ($src -join ' ')
Set-Content -Path $argFile -Value $line -Encoding ascii
& $jdk ("@" + $argFile) 2>&1 | Select-Object -First 60
Write-Output "EXIT=$LASTEXITCODE"
