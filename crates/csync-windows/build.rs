fn main() {
    embed_languages();
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc") {
        let root = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
        let manifest = root.join("app.manifest");
        println!("cargo:rerun-if-changed={}", manifest.display());
        println!("cargo:rustc-link-arg=/MANIFEST:EMBED");
        println!("cargo:rustc-link-arg=/MANIFESTINPUT:{}", manifest.display());
        let icon = root.join("assets/app.ico");
        println!("cargo:rerun-if-changed={}", icon.display());
        let version = std::env::var("CARGO_PKG_VERSION").unwrap();
        let language: serde_json::Value = serde_json::from_slice(
            &std::fs::read(root.join("../../locales/zh-Hans.json")).unwrap(),
        )
        .unwrap();
        let description = language["messages"]["app.description"]
            .as_str()
            .unwrap_or("app.description")
            .replace('\\', "\\\\")
            .replace('"', "\\\"");
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
      VALUE "FileDescription", "{description}\0"
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

fn embed_languages() {
    let root = std::path::PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap())
        .join("../../locales");
    println!("cargo:rerun-if-changed={}", root.display());
    let schema: serde_json::Value =
        serde_json::from_slice(&std::fs::read(root.join("messages.json")).unwrap()).unwrap();
    let mut catalog = serde_json::Map::new();
    for entry in std::fs::read_dir(&root).unwrap() {
        let path = entry.unwrap().path();
        if path.extension().and_then(|s| s.to_str()) != Some("json")
            || path.file_name().unwrap() == "messages.json"
        {
            continue;
        }
        let value: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&path).unwrap()).expect("invalid language JSON");
        let locale = value["_meta"]["locale"]
            .as_str()
            .expect("language locale missing");
        if locale == "qps-keys" {
            assert!(
                value["messages"].as_object().is_some_and(|m| m.is_empty()),
                "qps-keys must stay empty"
            );
        }
        assert_eq!(
            path.file_stem().unwrap().to_str().unwrap(),
            locale,
            "language filename must match locale"
        );
        assert!(
            value["_meta"]["nativeName"]
                .as_str()
                .is_some_and(|s| !s.is_empty())
        );
        assert!(matches!(
            value["_meta"]["direction"].as_str(),
            Some("ltr" | "rtl")
        ));
        for (key, translation) in value["messages"]
            .as_object()
            .expect("messages must be an object")
        {
            assert!(schema.get(key).is_some(), "unknown translation key: {key}");
            if let Some(text) = translation.as_str() {
                validate_parameters(key, text, &schema[key]);
            } else {
                let forms = translation
                    .as_object()
                    .expect("translation must be a string or plural object");
                assert!(
                    schema[key]["parameters"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .any(|v| v == "count")
                );
                assert!(
                    forms.contains_key("other"),
                    "plural must define other: {key}"
                );
                for (form, text) in forms {
                    assert!(matches!(
                        form.as_str(),
                        "zero" | "one" | "two" | "few" | "many" | "other"
                    ));
                    validate_parameters(
                        key,
                        text.as_str().expect("plural form must be text"),
                        &schema[key],
                    );
                }
            }
        }
        assert!(
            catalog.insert(locale.into(), value).is_none(),
            "duplicate locale"
        );
    }
    assert!(catalog.contains_key("zh-Hans") && catalog.contains_key("qps-keys"));
    let out =
        std::path::PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("csync-locales.json");
    std::fs::write(out, serde_json::to_vec(&catalog).unwrap()).unwrap();
}

fn validate_parameters(key: &str, text: &str, schema: &serde_json::Value) {
    if text.is_empty() {
        return;
    } // Empty translations intentionally display the key.
    let expected: std::collections::BTreeSet<_> = schema["parameters"]
        .as_array()
        .unwrap()
        .iter()
        .map(|p| p.as_str().unwrap())
        .collect();
    let mut actual = std::collections::BTreeSet::new();
    let mut rest = text;
    while let Some(start) = rest.find('{') {
        let end = rest[start..]
            .find('}')
            .expect("unclosed translation parameter")
            + start;
        actual.insert(&rest[start + 1..end]);
        rest = &rest[end + 1..];
    }
    assert_eq!(actual, expected, "translation parameters differ: {key}");
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
