#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
#![allow(unsafe_op_in_unsafe_fn)]

use crate::i18n::msg;

mod clipboard;
#[cfg(test)]
mod clipboard_tests;
mod i18n;
mod server;
mod storage;
#[cfg(test)]
mod tests;
mod ui;

fn main() {
    if let Err(error) = ui::run() {
        // Never include clipboard content or enrollment credentials in diagnostics.
        ui::show_error(&msg(
            "windows.start_failed",
            &[("error", format!("{error}"))],
        ));
    }
}
