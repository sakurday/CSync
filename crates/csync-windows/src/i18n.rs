use serde_json::{Value, json};
use std::sync::{LazyLock, RwLock};

pub static CATALOG: LazyLock<Value> = LazyLock::new(|| {
    serde_json::from_str(include_str!(concat!(
        env!("OUT_DIR"),
        "/csync-locales.json"
    )))
    .expect("build-validated language catalog")
});
static LANGUAGE: LazyLock<RwLock<String>> = LazyLock::new(|| RwLock::new("system".into()));

pub fn set_language(value: &str) -> bool {
    if value != "system" && CATALOG.get(value).is_none() {
        return false;
    }
    *LANGUAGE.write().unwrap() = value.into();
    true
}

pub fn locale() -> String {
    let selected = LANGUAGE.read().unwrap().clone();
    if selected != "system" {
        return selected;
    }
    // Read the user's UI language, rather than the process environment or ANSI code page.
    let mut buffer = [0u16; 256];
    let length = unsafe {
        windows_sys::Win32::Globalization::GetUserPreferredUILanguages(
            windows_sys::Win32::Globalization::MUI_LANGUAGE_NAME,
            &mut 0,
            buffer.as_mut_ptr(),
            &mut (buffer.len() as u32),
        )
    };
    let end = buffer.iter().position(|c| *c == 0).unwrap_or(0);
    let tag = if length != 0 {
        String::from_utf16_lossy(&buffer[..end])
    } else {
        String::new()
    };
    resolve_system(&tag)
}

fn resolve_system(tag: &str) -> String {
    if CATALOG.get(tag).is_some() {
        return tag.into();
    }
    let language = tag.split('-').next().unwrap_or("");
    let script = if language == "zh" {
        if tag.contains("Hant")
            || tag.ends_with("-TW")
            || tag.ends_with("-HK")
            || tag.ends_with("-MO")
        {
            "zh-Hant"
        } else {
            "zh-Hans"
        }
    } else {
        language
    };
    if CATALOG.get(script).is_some() {
        script.into()
    } else if language == "en" && CATALOG.get("en-US").is_some() {
        "en-US".into()
    } else {
        "zh-Hans".into()
    }
}

pub fn msg(key: &str, args: &[(&str, String)]) -> String {
    let args: serde_json::Map<String, Value> = args
        .iter()
        .map(|(k, v)| ((*k).into(), Value::String(v.clone())))
        .collect();
    json!({"csyncKey": key, "args": args}).to_string()
}
pub fn tr(key: &str, args: &[(&str, String)]) -> String {
    render(&msg(key, args))
}
pub fn render(value: &str) -> String {
    render_in(value, &locale(), 0)
}

fn render_in(value: &str, locale: &str, depth: usize) -> String {
    if depth > 8 {
        return value.into();
    }
    let Ok(message) = serde_json::from_str::<Value>(value) else {
        return value.into();
    };
    let Some(key) = message["csyncKey"].as_str() else {
        return value.into();
    };
    let Some(template) = CATALOG[locale]["messages"][key]
        .as_str()
        .filter(|s| !s.is_empty())
    else {
        return key.into();
    };
    let mut output = String::new();
    let mut rest = template;
    while let Some(start) = rest.find('{') {
        output.push_str(&rest[..start]);
        let Some(end) = rest[start..].find('}').map(|i| start + i) else {
            output.push_str(&rest[start..]);
            return output;
        };
        let name = &rest[start + 1..end];
        if let Some(arg) = message["args"][name].as_str() {
            output.push_str(&render_in(arg, locale, depth + 1));
        } else {
            output.push_str(&rest[start..=end]);
        }
        rest = &rest[end + 1..];
    }
    output.push_str(rest);
    output
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn missing_and_test_language_show_only_key() {
        for language in ["qps-keys", "missing-language"] {
            assert_eq!(
                render_in(
                    &msg("sync.connected", &[("transport", "secret host".into())]),
                    language,
                    0
                ),
                "sync.connected"
            );
        }
        assert_eq!(
            render_in(&msg("missing.key", &[]), "zh-Hans", 0),
            "missing.key"
        );
    }
    #[test]
    fn nested_messages_retranslate_and_arguments_are_not_templates() {
        let value = msg(
            "sync.connected",
            &[(
                "transport",
                msg("transport.tcp", &[("host", "{device} $1 中文".into())]),
            )],
        );
        assert_eq!(
            render_in(&value, "zh-Hans", 0),
            "已连接 · 网络 · {device} $1 中文"
        );
        assert_eq!(render_in(&value, "qps-keys", 0), "sync.connected");
        assert_eq!(resolve_system("zh-SG"), "zh-Hans");
    }
    #[test]
    fn english_system_languages_and_native_messages() {
        for tag in ["en-US", "en-GB", "en-SG", "en"] {
            assert_eq!(resolve_system(tag), "en-US");
        }
        assert_eq!(resolve_system("fr-FR"), "zh-Hans");
        let value = msg(
            "sync.connected",
            &[(
                "transport",
                msg("transport.tcp", &[("host", "{device} $1 🙂".into())]),
            )],
        );
        assert_eq!(
            render_in(&value, "en-US", 0),
            "Connected · Network · {device} $1 🙂"
        );
        assert_eq!(
            render_in(&msg("windows.tray_open", &[]), "en-US", 0),
            "Open CSync"
        );
    }
}
