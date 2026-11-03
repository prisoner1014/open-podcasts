import os
import sys
import subprocess
import shutil
import zipfile
import time

BASE_DIR = r"C:\Users\teimo\.t3\scratch\2026-11-02-make-me-a-new-aroudn-4ba9986a"
APP_DIR = os.path.join(BASE_DIR, "android_app")
BUILD_DIR = os.path.join(APP_DIR, "build")
GEN_DIR = os.path.join(BUILD_DIR, "gen")
OBJ_DIR = os.path.join(BUILD_DIR, "obj")
DEX_DIR = os.path.join(BUILD_DIR, "dex")

SDK_DIR = r"C:\Users\teimo\AppData\Local\Android\Sdk"
BUILD_TOOLS = os.path.join(SDK_DIR, "build-tools", "34.0.0")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8 = os.path.join(BUILD_TOOLS, "d8.bat")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
PLATFORM_JAR = os.path.join(SDK_DIR, "platforms", "android-34", "android.jar")
ADB = os.path.join(SDK_DIR, "platform-tools", "adb.exe")
JAVAC = r"C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot\bin\javac.exe"
KEYSTORE = os.path.join(BUILD_DIR, "debug.keystore")

ARTIFACT_DIR = r"C:\Users\teimo\.t3\userdata\providers\antigravity\ac0a3dfd6dddb20962cecff6ee5fe65e19d3923be20e52c5ab52ff877f7e4c32\antigravity-acp\brain\b8362d5f-615b-433d-8e2c-9247f2c4e283"

os.makedirs(GEN_DIR, exist_ok=True)
os.makedirs(OBJ_DIR, exist_ok=True)
os.makedirs(DEX_DIR, exist_ok=True)

def run(cmd, desc):
    print(f"[*] {desc}...")
    res = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if res.returncode != 0:
        print(f"[!] FAILED: {desc}")
        print("STDOUT:", res.stdout)
        print("STDERR:", res.stderr)
        sys.exit(1)
    if res.stdout.strip():
        print("   ", res.stdout.strip())
    return res

# 1. Compile resources
compiled_res = os.path.join(BUILD_DIR, "compiled_res.zip")
run(f'"{AAPT2}" compile --dir "{os.path.join(APP_DIR, "res")}" -o "{compiled_res}"', "Compiling resources")

# 2. Link resources & assets
base_apk = os.path.join(BUILD_DIR, "base.apk")
if os.path.exists(base_apk):
    os.remove(base_apk)
manifest = os.path.join(APP_DIR, "AndroidManifest.xml")
assets = os.path.join(APP_DIR, "assets")
run(f'"{AAPT2}" link -I "{PLATFORM_JAR}" --manifest "{manifest}" -o "{base_apk}" --java "{GEN_DIR}" -A "{assets}" "{compiled_res}"', "Linking resources and assets")

# 3. Find all Java files (R.java, MainActivity.java, AutoMediaBrowserService.java, etc.)
java_files = []
for root, dirs, files in os.walk(GEN_DIR):
    for f in files:
        if f.endswith(".java"):
            java_files.append(f'"{os.path.join(root, f)}"')
for root, dirs, files in os.walk(os.path.join(APP_DIR, "src")):
    for f in files:
        if f.endswith(".java"):
            java_files.append(f'"{os.path.join(root, f)}"')

# 4. Compile Java
run(f'"{JAVAC}" -cp "{PLATFORM_JAR}" -d "{OBJ_DIR}" ' + " ".join(java_files), "Compiling Java with javac")

# 5. Find all .class files
class_files = []
for root, dirs, files in os.walk(OBJ_DIR):
    for f in files:
        if f.endswith(".class"):
            class_files.append(f'"{os.path.join(root, f)}"')

# 6. Run d8
run(f'"{D8}" --lib "{PLATFORM_JAR}" --output "{DEX_DIR}" ' + " ".join(class_files), "Dexing with d8")

# 7. Add classes.dex into base.apk
classes_dex = os.path.join(DEX_DIR, "classes.dex")
print("[*] Adding classes.dex to base.apk...")
with zipfile.ZipFile(base_apk, "a") as z:
    z.write(classes_dex, "classes.dex")

# 8. Zipalign
aligned_apk = os.path.join(BUILD_DIR, "aligned.apk")
if os.path.exists(aligned_apk):
    os.remove(aligned_apk)
run(f'"{ZIPALIGN}" -f -p 4 "{base_apk}" "{aligned_apk}"', "Zipaligning APK")

# 9. Sign APK
final_apk = os.path.join(BASE_DIR, "OpenPodcasts.apk")
if os.path.exists(final_apk):
    os.remove(final_apk)
run(f'"{APKSIGNER}" sign --ks "{KEYSTORE}" --ks-pass pass:android --out "{final_apk}" "{aligned_apk}"', "Signing APK")

# Copy to artifacts
shutil.copy2(final_apk, os.path.join(ARTIFACT_DIR, "OpenPodcasts.apk"))
print("[+] APK built successfully:", final_apk, f"({os.path.getsize(final_apk)} bytes)")

# 10. Install on physical device
print("[*] Installing on Samsung device...")
install_res = run(f'"{ADB}" install -r -d "{final_apk}"', "Installing APK on phone")

# 11. Launch app
print("[*] Launching com.openpodcasts.app...")
run(f'"{ADB}" shell am start -S -n com.openpodcasts.app/.MainActivity', "Launching MainActivity")

# 12. Wait for rendering and screenshot
time.sleep(3)
screenshot_remote = "/sdcard/openpodcasts_phone.png"
screenshot_local = os.path.join(ARTIFACT_DIR, "openpodcasts_phone.png")
screenshot_workspace = os.path.join(BASE_DIR, "openpodcasts_phone.png")

run(f'"{ADB}" shell screencap -p "{screenshot_remote}"', "Capturing device screenshot")
run(f'"{ADB}" pull "{screenshot_remote}" "{screenshot_local}"', "Pulling screenshot to artifacts")
shutil.copy2(screenshot_local, screenshot_workspace)
print("[+] Screenshot captured:", screenshot_local)
