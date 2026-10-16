for ($taskWait = 0; $taskWait -lt 70; $taskWait++) { Start-Sleep -Seconds 1 }
Start-Process -FilePath 'C:\Program Files (x86)\FanControl\FanControl.exe' -WorkingDirectory 'C:\Program Files (x86)\FanControl' -ArgumentList @('-c','userConfig.json') -WindowStyle Hidden
