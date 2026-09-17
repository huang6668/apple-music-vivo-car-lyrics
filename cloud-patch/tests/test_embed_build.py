import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
import importlib.util


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "cloud-patch/embed-ampp.sh"
APK_NAME = "apple-music-vivo-car-lyrics-ampp-npatched.apk"


class EmbedBuildTest(unittest.TestCase):
    def test_catalog_query_patch_is_scoped_and_rejects_drift(self):
        spec = importlib.util.spec_from_file_location(
            "catalog_patch", ROOT / "cloud-patch/ampp/patch_catalog_query.py"
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        method = (
            ".method private final findDirectCatalogQueryMethod"
            "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/reflect/Method;\n"
            "    .locals 10\n"
            "    return-object v0\n"
            ".end method"
        )
        before = ".method public unrelated()V\n    return-void\n.end method\n"
        after = "\n.method public after()V\n    return-void\n.end method\n"
        patched = module.patch(before + method + after)
        self.assertTrue(patched.startswith(before))
        self.assertTrue(patched.endswith(after))
        self.assertIn("invoke-static {p1, p2}", patched)
        self.assertIn("CatalogQueryMethod;->resolve", patched)
        self.assertNotIn(".locals 10", patched)
        for invalid in ("", method + "\n" + method, method.replace("private final", "public")):
            with self.assertRaises(ValueError):
                module.patch(invalid)

    def test_native_directory_patch_preserves_registers_and_rejects_drift(self):
        spec = importlib.util.spec_from_file_location(
            "native_patch", ROOT / "cloud-patch/ampp/patch_native_directory.py"
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        original = (
            "    iget-object v2, v1, "
            "Landroid/content/pm/ApplicationInfo;->nativeLibraryDir:Ljava/lang/String;\n"
        )
        patched = module.patch(original)
        self.assertIn("invoke-static {v1}", patched)
        self.assertIn("move-result-object v2", patched)
        for invalid in ("", original + original):
            with self.assertRaises(ValueError):
                module.patch(invalid)

    def test_failed_preflight_removes_stale_deliverables(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "out/embed-ampp"
            report = output / "report"
            report.mkdir(parents=True)
            apk = output / APK_NAME
            checksum = output / (APK_NAME + ".sha256")
            apk.write_bytes(b"unverified")
            checksum.write_text("stale checksum")
            diagnostic = report / "npatch.log"
            diagnostic.write_text("keep failure diagnostics")
            result = subprocess.run(
                ["bash", str(SCRIPT)],
                cwd=root,
                env={
                    **os.environ,
                    "ANDROID_SDK_ROOT": str(root / "sdk"),
                    "BUILD_TOOLS_VERSION": "35.0.0",
                    "RUNNER_TEMP": str(root / "runner"),
                },
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("Patched APK is missing", result.stderr)
            self.assertFalse(apk.exists())
            self.assertFalse(checksum.exists())
            self.assertEqual(diagnostic.read_text(), "keep failure diagnostics")

    def test_pinned_cli_does_not_toggle_logging_off(self):
        source = SCRIPT.read_text()
        command = source.split("java -Xmx4g ", 1)[1].split("2>&1 | tee", 1)[0]
        self.assertNotIn("--outputLog", shlex.split(command.replace("\\\n", " ")))
        self.assertIn('config.get("outputLog") is not True', source)

    def test_optional_upload_requires_successful_embed_step(self):
        workflow = (ROOT / ".github/workflows/apk-pipeline.yml").read_text()
        embed = workflow.split("- name: Embed AM++ module with NPatch", 1)[1]
        self.assertIn("id: embed_ampp", embed.split("- name:", 1)[0])
        upload = workflow.split("- name: Upload AM++-embedded APK", 1)[1]
        condition = upload.splitlines()[1]
        self.assertIn("steps.embed_ampp.outcome == 'success'", condition)
        self.assertIn(APK_NAME + ".sha256", condition)


if __name__ == "__main__":
    unittest.main()
