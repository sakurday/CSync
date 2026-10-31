fn main() {
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        let root = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
        let manifest = root.join("app.manifest");
        println!("cargo:rerun-if-changed={}", manifest.display());
        println!("cargo:rustc-link-arg=/MANIFEST:EMBED");
        println!("cargo:rustc-link-arg=/MANIFESTINPUT:{}", manifest.display());
        let icon = root.join("assets/app.ico");
        println!("cargo:rerun-if-changed={}", icon.display());
        let version = std::env::var("CARGO_PKG_VERSION").unwrap();
        let numeric = format!("{},0", version.replace('.', ","));
        let output = std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap());
        let resource = output.join("app.rc");
        std::fs::write(
            &resource,
            format!(
                r#"
#pragma code_page(65001)
101 ICON "{}"
1 VERSIONINFO
FILEVERSION {numeric}
PRODUCTVERSION {numeric}
FILEFLAGSMASK 0x3fL
FILEOS 0x40004L
FILETYPE 0x1L
BEGIN
  BLOCK "StringFileInfo"
  BEGIN
    BLOCK "040904b0"
    BEGIN
      VALUE "FileDescription", "在手机和电脑间同步剪贴板\0"
      VALUE "FileVersion", "{version}\0"
      VALUE "ProductName", "CSync\0"
      VALUE "ProductVersion", "{version}\0"
      VALUE "OriginalFilename", "CSync.exe\0"
    END
  END
  BLOCK "VarFileInfo"
  BEGIN
    VALUE "Translation", 0x0409, 1200
  END
END
"#,
                icon.display().to_string().replace('\\', "/")
            ),
        )
        .unwrap();
        let compiled = output.join("app.res");
        let compiler = resource_compiler();
        let status = std::process::Command::new(compiler)
            .arg("/nologo")
            .arg("/fo")
            .arg(&compiled)
            .arg(&resource)
            .status()
            .expect("Cannot start Windows SDK rc.exe");
        assert!(
            status.success(),
            "Windows icon/version resource compilation failed"
        );
        println!("cargo:rustc-link-arg={}", compiled.display());
    }
}

fn resource_compiler() -> std::path::PathBuf {
    if let Some(rc) = std::env::var_os("RC") {
        return rc.into();
    }
    if let Ok(paths) = std::env::var("PATH") {
        for folder in std::env::split_paths(&paths) {
            let rc = folder.join("rc.exe");
            if rc.is_file() {
                return rc;
            }
        }
    }
    let kits = std::path::PathBuf::from(
        std::env::var_os("ProgramFiles(x86)").unwrap_or_else(|| "C:\\Program Files (x86)".into()),
    )
    .join("Windows Kits/10/bin");
    let mut versions: Vec<_> = std::fs::read_dir(kits)
        .expect("Install Windows SDK or set RC to rc.exe")
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|p| p.file_name().unwrap().to_string_lossy().starts_with("10."))
        .collect();
    versions.sort();
    for version in versions.into_iter().rev() {
        let rc = version.join("x64/rc.exe");
        if rc.is_file() {
            return rc;
        }
    }
    panic!("Install Windows SDK or set RC to rc.exe");
}
