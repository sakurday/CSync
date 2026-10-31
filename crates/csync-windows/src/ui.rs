use crate::{
    clipboard::Clipboard,
    server::{Host, UiEvent, UiSink, WAKE},
    storage::{Store, set_startup, wide},
};
use anyhow::{Context, Result, ensure};
use csync_core::protocol::Clip;
use std::{
    ptr::{null, null_mut},
    sync::{Arc, atomic::Ordering, mpsc},
};
use windows_sys::Win32::{
    Foundation::*,
    Graphics::Gdi::*,
    System::{LibraryLoader::GetModuleHandleW, Threading::CreateMutexW},
    UI::{Controls::*, Shell::*, WindowsAndMessaging::*},
};

const TRAY: u32 = WM_APP + 10;
const PAUSE: i32 = 112;
const EXIT: i32 = 114;
const TRAY_TIMER: usize = 2;

struct App {
    host: Arc<Host>,
    receiver: mpsc::Receiver<UiEvent>,
    clipboard: Clipboard,
    webview: Option<wry::WebView>,
    last_state: std::cell::RefCell<String>,
    commands: mpsc::Receiver<String>,
    command_tx: mpsc::Sender<String>,
    pending_pair: Option<(
        String,
        String,
        tokio::sync::oneshot::Sender<bool>,
        std::time::Instant,
    )>,
    sequence: u64,
    taskbar_created: u32,
    running_port: u16,
    tray_registered: Option<bool>,
}

pub fn show_error(message: &str) {
    unsafe {
        MessageBoxW(
            null_mut(),
            wide(message).as_ptr(),
            wide("CSync").as_ptr(),
            MB_OK | MB_ICONERROR,
        );
    }
}

pub fn run() -> Result<()> {
    unsafe {
        let mutex = CreateMutexW(null(), 0, wide("Local\\CSync.Desktop.v1").as_ptr());
        ensure!(!mutex.is_null(), "cannot create single-instance lock");
        if GetLastError() == ERROR_ALREADY_EXISTS {
            let existing = FindWindowW(wide("CSync.Main").as_ptr(), null());
            if !existing.is_null() {
                PostMessageW(existing, WM_COMMAND, 120, 0);
                SetForegroundWindow(existing);
            }
            CloseHandle(mutex);
            return Ok(());
        }
        SetProcessDPIAware();
        InitCommonControlsEx(&INITCOMMONCONTROLSEX {
            dwSize: std::mem::size_of::<INITCOMMONCONTROLSEX>() as u32,
            dwICC: ICC_STANDARD_CLASSES,
        });
        let store = Store::open()?;
        let config = store.config()?;
        let identity = store.identity()?;
        let (tx, receiver) = mpsc::channel();
        let host = Host::new(store, config.clone(), identity, UiSink::new(tx))?;
        let instance = GetModuleHandleW(null());
        let mut class: WNDCLASSW = std::mem::zeroed();
        class.lpfnWndProc = Some(window_proc);
        class.hInstance = instance;
        // Keep backing buffers alive across Win32 calls.
        let class_name = wide("CSync.Main");
        class.lpszClassName = class_name.as_ptr();
        class.hCursor = LoadCursorW(null_mut(), IDC_ARROW);
        class.hIcon = LoadIconW(instance, 101usize as *const u16);
        class.hbrBackground = (COLOR_WINDOW + 1) as HBRUSH;
        ensure!(
            RegisterClassW(&class) != 0,
            "cannot register application window"
        );
        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            wide("CSync · 剪贴板同步").as_ptr(),
            WS_OVERLAPPEDWINDOW,
            CW_USEDEFAULT,
            CW_USEDEFAULT,
            1000,
            810,
            null_mut(),
            null_mut(),
            instance,
            null(),
        );
        ensure!(!hwnd.is_null(), "cannot create application window");
        let (command_tx, commands) = mpsc::channel();
        let mut app = Box::new(App {
            host: host.clone(),
            receiver,
            clipboard: Clipboard::new(hwnd)?,
            webview: None,
            last_state: std::cell::RefCell::new(String::new()),
            commands,
            command_tx,
            pending_pair: None,
            sequence: 0,
            taskbar_created: RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()),
            running_port: config.port,
            tray_registered: None,
        });
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, app.as_mut() as *mut App as isize);
        host.ui.hwnd.store(hwnd as isize, Ordering::Relaxed);

        restore_tray(hwnd, &mut app);
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()?;
        let network = host.clone();
        runtime.spawn(async move {
            if let Err(error) = crate::server::serve(network.clone()).await {
                network.status(format!("网络服务启动失败：{error}"));
            }
        });
        if config.bluetooth {
            crate::bluetooth::start(host.clone(), runtime.handle().clone());
        }
        refresh(hwnd, &app);
        if !std::env::args().any(|s| s == "--background") || app.tray_registered != Some(true) {
            if let Err(error) = open_webview(hwnd, &mut app) {
                show_error(&format!(
                    "无法打开设置界面，请确认已安装 Microsoft Edge WebView2 Runtime：{error:#}"
                ));
            }
        }
        let mut message = std::mem::zeroed();
        while GetMessageW(&mut message, null_mut(), 0, 0) > 0 {
            if IsDialogMessageW(hwnd, &message) == 0 {
                TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        host.ui.hwnd.store(0, Ordering::Relaxed);
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, 0);
        notify_icon(hwnd, NIM_DELETE);
        drop(app);
        DestroyWindow(hwnd);

        CloseHandle(mutex);
        runtime.shutdown_timeout(std::time::Duration::from_secs(1));
    }
    Ok(())
}

struct WindowHandle(HWND);
impl raw_window_handle::HasWindowHandle for WindowHandle {
    fn window_handle(
        &self,
    ) -> std::result::Result<raw_window_handle::WindowHandle<'_>, raw_window_handle::HandleError>
    {
        let raw = raw_window_handle::Win32WindowHandle::new(
            std::num::NonZeroIsize::new(self.0 as isize).unwrap(),
        );
        Ok(unsafe {
            raw_window_handle::WindowHandle::borrow_raw(raw_window_handle::RawWindowHandle::Win32(
                raw,
            ))
        })
    }
}
unsafe fn open_webview(hwnd: HWND, app: &mut App) -> Result<()> {
    if app.webview.is_none() {
        let mut context = wry::WebContext::new(Some(app.host.store.directory.join("webview")));
        let tx = app.command_tx.clone();
        let target = hwnd as isize;
        let mut rect = RECT::default();
        GetClientRect(hwnd, &mut rect);
        let html = include_str!("../web/index.html")
            .replace("/* MATERIAL_BUNDLE */", include_str!("../web/bundle.js"))
            .replace(
                "<!-- OPEN_SOURCE_LIBRARIES -->",
                include_str!("../web/open-source.html"),
            )
            .replace(
                "APP_ICON_BASE64",
                &base64::Engine::encode(
                    &base64::engine::general_purpose::STANDARD,
                    include_bytes!("../../../icon_512.png"),
                ),
            );
        let page_url = format!(
            "data:text/html;charset=utf-8;base64,{}",
            base64::Engine::encode(&base64::engine::general_purpose::STANDARD, html.as_bytes())
        );
        let view = wry::WebViewBuilder::new_with_web_context(&mut context)
            .with_html(html)
            .with_bounds(wry::Rect {
                position: wry::dpi::PhysicalPosition::new(0, 0).into(),
                size: wry::dpi::PhysicalSize::new(rect.right, rect.bottom).into(),
            })
            .with_devtools(false)
            .with_new_window_req_handler(|_, _| wry::NewWindowResponse::Deny)
            .with_navigation_handler(move |url| url == "about:blank" || url == page_url)
            .with_ipc_handler(move |request| {
                if request.body().len() < 4096 {
                    let _ = tx.send(request.body().clone());
                    PostMessageW(target as HWND, WAKE, 0, 0);
                }
            })
            .build_as_child(&WindowHandle(hwnd))
            .context("WebView2 初始化失败")?;
        app.last_state.borrow_mut().clear();
        app.webview = Some(view);
    }
    SetTimer(hwnd, 1, 1000, None);
    ShowWindow(hwnd, SW_SHOW);
    SetForegroundWindow(hwnd);
    Ok(())
}
unsafe fn refresh(_hwnd: HWND, app: &App) {
    if let Some(view) = &app.webview {
        let config = app.host.config.lock().unwrap();
        let pairing = app
            .pending_pair
            .as_ref()
            .map(|(code, name, _, _)| serde_json::json!({"code":code,"name":name}));
        let state = serde_json::json!({
            "version": env!("CARGO_PKG_VERSION"),
            "paused": app.host.paused.load(Ordering::Relaxed),
            "trayAvailable": app.tray_registered == Some(true),
            "status": *app.host.status.lock().unwrap(),
            "online": app.host.active.lock().unwrap().len(),
            "peers": config.peers.values().collect::<Vec<_>>(),
            "port": config.port, "runningPort": app.running_port,
            "public_access": config.public_access, "bluetooth": config.bluetooth,
            "auto_start": config.auto_start,
            "addresses": local_addresses().unwrap_or_default(),
            "pairingOpen": app.host.trust.lock().unwrap().pairing_open(),
            "pairing": pairing,
        });
        let serialized = state.to_string();
        if *app.last_state.borrow() == serialized {
            return;
        }
        *app.last_state.borrow_mut() = serialized;
        let _ = view.evaluate_script(&format!(
            "window.updateState && window.updateState({state})"
        ));
    }
}
unsafe fn command(hwnd: HWND, app: &mut App, body: &str) -> Result<()> {
    let value: serde_json::Value = serde_json::from_str(body)?;
    match value["action"].as_str().unwrap_or("") {
        "ready" => {
            app.last_state.borrow_mut().clear();
        }
        "quit" => PostQuitMessage(0),
        "pause" => {
            app.host.paused.fetch_xor(true, Ordering::Relaxed);
        }
        "pair" => {
            begin_pairing(hwnd, app)?;
        }
        "closePair" => {
            app.host.trust.lock().unwrap().close_pairing();
        }
        "confirmPair" => {
            if let Some((_, _, reply, until)) = app.pending_pair.take() {
                let _ = reply.send(
                    value["approved"].as_bool().unwrap_or(false) && until.elapsed().as_secs() < 55,
                );
            }
        }
        "revoke" => {
            app.host.revoke_all()?;
        }
        "save" => {
            let mut config = app.host.config.lock().unwrap();
            let mut next = config.clone();
            let port = value["port"]
                .as_u64()
                .filter(|p| *p > 0 && *p <= 65535)
                .ok_or_else(|| anyhow::anyhow!("端口范围为 1–65535"))?;
            next.port = port as u16;
            next.public_access = value["public_access"].as_bool().unwrap_or(false);
            next.bluetooth = value["bluetooth"].as_bool().unwrap_or(false);
            next.auto_start = value["auto_start"].as_bool().unwrap_or(false);
            let restart = next.port != config.port || next.bluetooth != config.bluetooth;
            set_startup(next.auto_start)?;
            app.host.store.save(&next)?;
            *config = next;
            drop(config);
            app.host.trust.lock().unwrap().close_pairing();
            app.host.status(if restart {
                "设置已保存；端口和蓝牙更改请退出后重新启动"
            } else {
                "设置已保存"
            });
        }
        _ => anyhow::bail!("未知操作"),
    }
    refresh(hwnd, app);
    Ok(())
}

unsafe extern "system" fn window_proc(hwnd: HWND, message: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut App;
    if ptr.is_null() {
        return DefWindowProcW(hwnd, message, w, l);
    }
    let app = &mut *ptr;
    if message == app.taskbar_created {
        restore_tray(hwnd, app);
        return 0;
    }
    match message {
        WM_CLOSE => {
            restore_tray(hwnd, app);
            if app.tray_registered != Some(true) {
                PostQuitMessage(0);
                return 0;
            }
            ShowWindow(hwnd, SW_HIDE);
            app.webview.take();
            KillTimer(hwnd, 1);
            if let Some((_, _, reply, _)) = app.pending_pair.take() {
                let _ = reply.send(false);
            }
            0
        }
        WM_QUERYENDSESSION => 1,
        WM_ENDSESSION if w != 0 => {
            PostQuitMessage(0);
            0
        }
        WM_CLIPBOARDUPDATE => {
            if !app.host.paused.load(Ordering::Relaxed) {
                if let Ok(Some(text)) = app.clipboard.read() {
                    // Enrollment material must never be forwarded to peers.
                    if !text.starts_with("csync://pair?") {
                        app.sequence += 1;
                        if let Ok(clip) = Clip::new(&app.host.id, app.sequence, text) {
                            app.host.publish(clip);
                        }
                    }
                }
            }
            0
        }
        WAKE => {
            while let Ok(event) = app.receiver.try_recv() {
                match event {
                    UiEvent::Refresh => {}
                    UiEvent::Status(status) => {
                        *app.host.status.lock().unwrap() = status;
                    }
                    UiEvent::Pair { pin, name, reply } => {
                        let code =
                            csync_core::protocol::text_hash(&format!("{}:{pin}", app.host.id))
                                [..12]
                                .to_string();
                        app.pending_pair = Some((code, name, reply, std::time::Instant::now()));
                        if let Err(error) = open_webview(hwnd, app) {
                            show_error(&error.to_string());
                        }
                    }
                    UiEvent::Remote { clip, reply } => {
                        let applied = !app.host.paused.load(Ordering::Relaxed)
                            && app.clipboard.write(&clip).is_ok();
                        if applied {
                            app.host.publish(clip);
                        }
                        let _ = reply.send(applied);
                    }
                }
            }
            while let Ok(body) = app.commands.try_recv() {
                if let Err(error) = command(hwnd, app, &body) {
                    app.host.status(error.to_string());
                }
            }
            refresh(hwnd, app);
            0
        }
        WM_TIMER if w == TRAY_TIMER => {
            restore_tray(hwnd, app);
            0
        }
        WM_TIMER => {
            if app
                .pending_pair
                .as_ref()
                .is_some_and(|(_, _, reply, start)| {
                    reply.is_closed() || start.elapsed().as_secs() >= 55
                })
            {
                if let Some((_, _, reply, _)) = app.pending_pair.take() {
                    let _ = reply.send(false);
                }
            }
            refresh(hwnd, app);
            0
        }
        WM_GETMINMAXINFO => {
            let info = &mut *(l as *mut MINMAXINFO);
            info.ptMinTrackSize = POINT { x: 640, y: 580 };
            0
        }
        WM_SIZE => {
            if let Some(view) = &app.webview {
                let mut rect = RECT::default();
                GetClientRect(hwnd, &mut rect);
                let _ = view.set_bounds(wry::Rect {
                    position: wry::dpi::PhysicalPosition::new(0, 0).into(),
                    size: wry::dpi::PhysicalSize::new(rect.right, rect.bottom).into(),
                });
            }
            0
        }
        WM_COMMAND => {
            match (w & 0xffff) as i32 {
                PAUSE => {
                    app.host.paused.fetch_xor(true, Ordering::Relaxed);
                    refresh(hwnd, app);
                }
                EXIT => PostQuitMessage(0),
                120 => {
                    restore_tray(hwnd, app);
                    if let Err(e) = open_webview(hwnd, app) {
                        show_error(&e.to_string());
                    }
                }
                _ => {}
            }
            0
        }
        TRAY => {
            if l as u32 == WM_LBUTTONDBLCLK {
                if let Err(e) = open_webview(hwnd, app) {
                    show_error(&e.to_string());
                }
            }
            if l as u32 == WM_RBUTTONUP {
                let menu = CreatePopupMenu();
                AppendMenuW(menu, MF_STRING, 120, wide("打开 CSync").as_ptr());
                AppendMenuW(
                    menu,
                    MF_STRING,
                    PAUSE as usize,
                    wide("暂停 / 恢复同步").as_ptr(),
                );
                AppendMenuW(menu, MF_SEPARATOR, 0, null());
                AppendMenuW(menu, MF_STRING, EXIT as usize, wide("退出").as_ptr());
                let mut point = std::mem::zeroed();
                GetCursorPos(&mut point);
                SetForegroundWindow(hwnd);
                let selected = TrackPopupMenu(
                    menu,
                    TPM_RETURNCMD | TPM_RIGHTBUTTON,
                    point.x,
                    point.y,
                    0,
                    hwnd,
                    null(),
                );
                DestroyMenu(menu);
                if selected == 120 {
                    if let Err(e) = open_webview(hwnd, app) {
                        show_error(&e.to_string());
                    }
                } else if selected != 0 {
                    PostMessageW(hwnd, WM_COMMAND, selected as usize, 0);
                }
                PostMessageW(hwnd, WM_NULL, 0, 0);
            }
            0
        }
        WM_DESTROY => {
            PostQuitMessage(0);
            0
        }
        WM_CTLCOLORSTATIC => {
            SetBkMode(w as HDC, TRANSPARENT as i32);
            SetTextColor(w as HDC, 0x002a_2319);
            GetStockObject(WHITE_BRUSH) as isize
        }
        _ => DefWindowProcW(hwnd, message, w, l),
    }
}

unsafe fn begin_pairing(_hwnd: HWND, app: &mut App) -> Result<()> {
    ensure!(
        app.host.config.lock().unwrap().port == app.running_port,
        "端口已更改，请退出并重新启动后再配对"
    );
    app.host.trust.lock().unwrap().open_pairing();
    Ok(())
}

fn local_addresses() -> Result<Vec<String>> {
    use windows_sys::Win32::{
        NetworkManagement::{IpHelper::*, Ndis::IfOperStatusUp},
        Networking::WinSock::{AF_INET, SOCKADDR_IN},
    };
    let mut hosts = Vec::new();
    // Connecting a UDP socket selects a route without sending a packet.
    if let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") {
        if socket.connect("192.0.2.1:9").is_ok() {
            if let Ok(address) = socket.local_addr() {
                if !address.ip().is_unspecified() && !address.ip().is_loopback() {
                    hosts.push(address.ip().to_string());
                }
            }
        }
    }
    unsafe {
        let flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
        let mut bytes = 0u32;
        GetAdaptersAddresses(AF_INET as u32, flags, null(), null_mut(), &mut bytes);
        if bytes > 0 {
            // usize storage ensures proper alignment for the returned structures.
            let mut buffer = vec![0usize; (bytes as usize).div_ceil(std::mem::size_of::<usize>())];
            let first = buffer.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
            let status = GetAdaptersAddresses(AF_INET as u32, flags, null(), first, &mut bytes);
            ensure!(status == 0, "无法读取网络地址（错误 {status}）");
            let mut adapter = first;
            while !adapter.is_null() {
                if (*adapter).OperStatus == IfOperStatusUp {
                    let mut entry = (*adapter).FirstUnicastAddress;
                    while !entry.is_null() {
                        let addr = (*entry).Address.lpSockaddr;
                        if !addr.is_null() && (*addr).sa_family == AF_INET {
                            let ip = std::net::Ipv4Addr::from(
                                (*addr.cast::<SOCKADDR_IN>())
                                    .sin_addr
                                    .S_un
                                    .S_addr
                                    .to_ne_bytes(),
                            );
                            if !ip.is_loopback() && !ip.is_unspecified() && !ip.is_link_local() {
                                let value = ip.to_string();
                                if !hosts.contains(&value) {
                                    hosts.push(value);
                                }
                            }
                        }
                        entry = (*entry).Next;
                    }
                }
                adapter = (*adapter).Next;
            }
        }
    }
    hosts.truncate(8);
    Ok(hosts)
}

unsafe fn restore_tray(hwnd: HWND, app: &mut App) {
    // Updating first also handles a duplicate TaskbarCreated broadcast without
    // adding a second icon. Retry independently of the WebView's refresh timer.
    let registered = notify_icon(hwnd, NIM_MODIFY) || notify_icon(hwnd, NIM_ADD);
    if !registered && app.tray_registered != Some(false) {
        app.host.status("托盘图标暂不可用，正在自动恢复");
    }
    app.tray_registered = Some(registered);
    SetTimer(
        hwnd,
        TRAY_TIMER,
        if registered { 30_000 } else { 5_000 },
        None,
    );
}

unsafe fn notify_icon(hwnd: HWND, operation: u32) -> bool {
    let mut data: NOTIFYICONDATAW = std::mem::zeroed();
    data.cbSize = std::mem::size_of_val(&data) as u32;
    data.hWnd = hwnd;
    data.uID = 1;
    data.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    data.uCallbackMessage = TRAY;
    data.hIcon = LoadIconW(GetModuleHandleW(null()), 101usize as *const u16);
    let tip = wide("CSync · 剪贴板同步");
    data.szTip[..tip.len()].copy_from_slice(&tip);
    Shell_NotifyIconW(operation, &data) != 0
}
