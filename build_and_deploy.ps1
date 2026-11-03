$ErrorActionPreference = "Stop"

$BASE_DIR = "C:\Users\teimo\.t3\scratch\2026-11-02-make-me-a-new-aroudn-4ba9986a"
$APP_DIR = "$BASE_DIR\android_app"
$BUILD_DIR = "$APP_DIR\build"
$GEN_DIR = "$BUILD_DIR\gen"
$OBJ_DIR = "$BUILD_DIR\obj"
$DEX_DIR = "$BUILD_DIR\dex"

$SDK_DIR = "C:\Users\teimo\AppData\Local\Android\Sdk"
$BUILD_TOOLS = "$SDK_DIR\build-tools\34.0.0"
$AAPT2 = "$BUILD_TOOLS\aapt2.exe"
$D8 = "$BUILD_TOOLS\d8.bat"
$ZIPALIGN = "$BUILD_TOOLS\zipalign.exe"
$APKSIGNER = "$BUILD_TOOLS\apksigner.bat"
$PLATFORM_JAR = "$SDK_DIR\platforms\android-34\android.jar"
$ADB = "$SDK_DIR\platform-tools\adb.exe"
$JAVAC = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin\javac.exe"
$KEYSTORE = "$BUILD_DIR\debug.keystore"
$DEVICE_TARGET = "-s RFGL705LSPK"

$ARTIFACT_DIR = "C:\Users\teimo\.t3\userdata\providers\antigravity\ac0a3dfd6dddb20962cecff6ee5fe65e19d3923be20e52c5ab52ff877f7e4c32\antigravity-acp\brain\b8362d5f-615b-433d-8e2c-9247f2c4e283"

New-Item -ItemType Directory -Force -Path $GEN_DIR, $OBJ_DIR, $DEX_DIR | Out-Null

Write-Host "[*] 1. Compiling resources..."
$compiled_res = "$BUILD_DIR\compiled_res.zip"
& $AAPT2 compile --dir "$APP_DIR\res" -o $compiled_res
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[*] 2. Linking resources and assets..."
$base_apk = "$BUILD_DIR\base.apk"
if (Test-Path $base_apk) { Remove-Item $base_apk -Force }
$manifest = "$APP_DIR\AndroidManifest.xml"
$assets = "$APP_DIR\assets"
& $AAPT2 link -I $PLATFORM_JAR --manifest $manifest -o $base_apk --java $GEN_DIR -A $assets $compiled_res
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[*] 3. Compiling Java..."
$javaFiles = @(Get-ChildItem -Path $GEN_DIR, "$APP_DIR\src" -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName)
& $JAVAC -cp $PLATFORM_JAR -d $OBJ_DIR $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[*] 4. Dexing with d8..."
$classFiles = @(Get-ChildItem -Path $OBJ_DIR -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName)
& cmd.exe /c "`"$D8`" --lib `"$PLATFORM_JAR`" --output `"$DEX_DIR`" $($classFiles -join ' ')"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[*] 5. Adding classes.dex to base.apk..."
$JAR = "C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin\jar.exe"
& $JAR -uf $base_apk -C $DEX_DIR classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar failed to add classes.dex" }

Write-Host "[*] 6. Zipaligning APK..."
$aligned_apk = "$BUILD_DIR\aligned.apk"
if (Test-Path $aligned_apk) { Remove-Item $aligned_apk -Force }
& $ZIPALIGN -f -p 4 $base_apk $aligned_apk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[*] 7. Signing APK..."
$final_apk = "$BASE_DIR\OpenPodcasts.apk"
if (Test-Path $final_apk) { Remove-Item $final_apk -Force }
& cmd.exe /c "`"$APKSIGNER`" sign --ks `"$KEYSTORE`" --ks-pass pass:android --out `"$final_apk`" `"$aligned_apk`""
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Copy-Item $final_apk "$ARTIFACT_DIR\OpenPodcasts.apk" -Force
Write-Host "[+] APK built successfully: $final_apk"

Write-Host "[*] 8. Installing APK on phone ($DEVICE_TARGET)..."
& $ADB -s RFGL705LSPK install -r -d $final_apk
if ($LASTEXITCODE -ne 0) { throw "adb install failed" }

Write-Host "[*] 9. Launching MainActivity..."
& $ADB -s RFGL705LSPK shell am start -S -n com.openpodcasts.app/.MainActivity
Start-Sleep -Seconds 3

Write-Host "[*] 10. Capturing device screenshot..."
$remoteShot = "/sdcard/openpodcasts_phone.png"
$localShot = "$ARTIFACT_DIR\openpodcasts_phone.png"
& $ADB -s RFGL705LSPK shell screencap -p $remoteShot
& $ADB -s RFGL705LSPK pull $remoteShot $localShot
Copy-Item $localShot "$BASE_DIR\openpodcasts_phone.png" -Force

Write-Host "[+] All done! Phone screenshot saved to $localShot"
