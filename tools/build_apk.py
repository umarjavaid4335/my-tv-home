"""Gradle-free APK build using the Android SDK command-line tools directly.

  python tools/build_apk.py android/probe      -> private/build/probe.apk
  python tools/build_apk.py android/home       -> private/build/home.apk

Each app dir contains AndroidManifest.xml, res/, src/ (Java, framework APIs only) and optional assets/.
Signing key: private/signing/release.jks (created on first build; back it up - updates need the same key).
SDK location: $ANDROID_SDK_DIR or private/sdk (build-tools in bt/, platform in plat/).
"""
import glob, os, secrets, shutil, subprocess, sys, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = ROOT / "private"
SDK = Path(os.environ.get("ANDROID_SDK_DIR", PRIVATE / "sdk"))
BT = next(p for p in (SDK / "bt").iterdir() if p.is_dir())
ANDROID_JAR = next((SDK / "plat").glob("*/android.jar"))
SIGN = PRIVATE / "signing"
MIN_API = "26"


def java_home():
    if os.environ.get("JAVA_HOME"):
        return Path(os.environ["JAVA_HOME"])
    hits = sorted(glob.glob(r"C:\Program Files\Microsoft\jdk-17*"))
    if not hits:
        sys.exit("JDK 17 not found; set JAVA_HOME")
    return Path(hits[-1])


JH = java_home()
ENV = dict(os.environ, JAVA_HOME=str(JH), PATH=str(JH / "bin") + os.pathsep + os.environ["PATH"])


def tool(name):
    for ext in (".exe", ".bat", ""):
        p = BT / (name + ext)
        if p.exists():
            return str(p)
    sys.exit("missing build tool " + name)


def run(*args):
    r = subprocess.run([str(a) for a in args], env=ENV, capture_output=True, text=True)
    if r.returncode:
        sys.exit("FAILED: %s\n%s%s" % (" ".join(map(str, args[:3])), r.stdout, r.stderr))
    return r.stdout


def ensure_key():
    SIGN.mkdir(parents=True, exist_ok=True)
    jks, props = SIGN / "release.jks", SIGN / "keystore.properties"
    if not jks.exists():
        pw = secrets.token_urlsafe(18)
        run(JH / "bin" / "keytool", "-genkeypair", "-keystore", jks, "-storepass", pw, "-keypass", pw,
            "-alias", "mytvhome", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=my-tv-home")
        props.write_text("storePassword=%s\nkeyAlias=mytvhome\n" % pw)
    kv = dict(l.split("=", 1) for l in props.read_text().splitlines() if "=" in l)
    return jks, kv["storePassword"]


def build(app_dir):
    app = Path(app_dir).resolve()
    name = app.name
    out = PRIVATE / "build" / name
    shutil.rmtree(out, ignore_errors=True)
    (out / "gen").mkdir(parents=True)
    (out / "classes").mkdir()
    run(tool("aapt2"), "compile", "--dir", app / "res", "-o", out / "res.zip")
    link = [tool("aapt2"), "link", "-I", ANDROID_JAR, "--manifest", app / "AndroidManifest.xml",
            "--java", out / "gen", "-o", out / "base.apk", "--auto-add-overlay", out / "res.zip"]
    # Assets = the app's own assets plus, for My Home, the git-ignored assistant key (owner chose to keep
    # keys on the TV). The merged copy lives only under private/build.
    assets = out / "assets"
    if (app / "assets").is_dir():
        shutil.copytree(app / "assets", assets)
    secrets = PRIVATE / "assistant.secrets.json"
    if name == "home" and secrets.exists():
        (assets / "secrets").mkdir(parents=True, exist_ok=True)
        shutil.copy(secrets, assets / "secrets" / "assistant.json")
    if assets.is_dir():
        link += ["-A", assets]
    run(*link)
    srcs = [str(p) for p in (app / "src").rglob("*.java")] + [str(p) for p in (out / "gen").rglob("*.java")]
    # java.* from the JDK's release-8 signatures (includes LambdaMetafactory); android.* from android.jar.
    # d8 below desugars lambdas for the device.
    run(JH / "bin" / "javac", "-nowarn", "-encoding", "UTF-8", "--release", "8",
        "-classpath", ANDROID_JAR, "-d", out / "classes", *srcs)
    # One jar instead of hundreds of .class paths (Windows command-line length limit).
    with zipfile.ZipFile(out / "classes.jar", "w") as jar:
        for p in (out / "classes").rglob("*.class"):
            jar.write(p, p.relative_to(out / "classes").as_posix())
    run(tool("d8"), "--release", "--min-api", MIN_API, "--lib", ANDROID_JAR, "--output", out, out / "classes.jar")
    shutil.copy(out / "base.apk", out / "unaligned.apk")
    with zipfile.ZipFile(out / "unaligned.apk", "a") as z:
        z.write(out / "classes.dex", "classes.dex")
    run(tool("zipalign"), "-f", "-p", "4", out / "unaligned.apk", out / "aligned.apk")
    jks, pw = ensure_key()
    final = PRIVATE / "build" / (name + ".apk")
    run(tool("apksigner"), "sign", "--ks", jks, "--ks-pass", "pass:" + pw, "--out", final, out / "aligned.apk")
    run(tool("apksigner"), "verify", final)
    print("built", final, "%d KB" % (final.stat().st_size // 1024))


if __name__ == "__main__":
    build(sys.argv[1])
