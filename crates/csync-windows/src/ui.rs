use crate::{
    clipboard::Clipboard,
    server::{Host, UiEvent, UiSink, WAKE},
    storage::{Store, set_startup, wide},
};
use anyhow::{Result, ensure};
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
const STATUS: i32 = 100;
const PORT: i32 = 102;
const PUBLIC: i32 = 103;
const BT: i32 = 104;
const STARTUP: i32 = 105;
const SAVE: i32 = 110;
const PAIR: i32 = 111;
const PAUSE: i32 = 112;
const REVOKE: i32 = 113;
const EXIT: i32 = 114;
const CODE: i32 = 115;
const PEERS: i32 = 116;
const CLOSE_PAIR: i32 = 117;

struct App {
    host: Arc<Host>,
    receiver: mpsc::Receiver<UiEvent>,
    clipboard: Clipboard,
    font: HFONT,
    title_font: HFONT,
    sequence: u64,
    taskbar_created: u32,
    running_port: u16,
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
                ShowWindow(existing, SW_SHOW);
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
        class.hIcon = LoadIconW(null_mut(), IDI_APPLICATION);
        class.hbrBackground = (COLOR_WINDOW + 1) as HBRUSH;
        ensure!(
            RegisterClassW(&class) != 0,
            "cannot register application window"
        );
        let hwnd = CreateWindowExW(
            0,
            class_name.as_ptr(),
            wide("CSync · 剪贴板同步").as_ptr(),
            WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX,
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
        let font = CreateFontW(
            -18,
            0,
            0,
            0,
            400,
            0,
            0,
            0,
            DEFAULT_CHARSET as u32,
            0,
            0,
            CLEARTYPE_QUALITY as u32,
            0,
            wide("Microsoft YaHei UI").as_ptr(),
        );
        let title_font = CreateFontW(
            -30,
            0,
            0,
            0,
            600,
            0,
            0,
            0,
            DEFAULT_CHARSET as u32,
            0,
            0,
            CLEARTYPE_QUALITY as u32,
            0,
            wide("Microsoft YaHei UI").as_ptr(),
        );
        let mut app = Box::new(App {
            host: host.clone(),
            receiver,
            clipboard: Clipboard::new(hwnd)?,
            font,
            title_font,
            sequence: 0,
            taskbar_created: RegisterWindowMessageW(wide("TaskbarCreated").as_ptr()),
            running_port: config.port,
        });
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, app.as_mut() as *mut App as isize);
        host.ui.hwnd.store(hwnd as isize, Ordering::Relaxed);
        build_controls(hwnd, &app);
        notify_icon(hwnd, NIM_ADD);
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
        if !std::env::args().any(|s| s == "--background") {
            ShowWindow(hwnd, SW_SHOW);
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
        DeleteObject(font);
        DeleteObject(title_font);
        CloseHandle(mutex);
        runtime.shutdown_timeout(std::time::Duration::from_secs(1));
    }
    Ok(())
}

unsafe fn control(
    hwnd: HWND,
    class: &str,
    text: &str,
    style: u32,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    id: i32,
    font: HFONT,
) -> HWND {
    let child = CreateWindowExW(
        if class == "EDIT" { WS_EX_CLIENTEDGE } else { 0 },
        wide(class).as_ptr(),
        wide(text).as_ptr(),
        WS_CHILD | WS_VISIBLE | style,
        x,
        y,
        w,
        h,
        hwnd,
        id as usize as _,
        GetModuleHandleW(null()),
        null(),
    );
    SendMessageW(child, WM_SETFONT, font as usize, 1);
    child
}
unsafe fn build_controls(hwnd: HWND, app: &App) {
    let f = app.font;
    let config = app.host.config.lock().unwrap();
    control(
        hwnd,
        "STATIC",
        "CSync",
        0,
        32,
        24,
        450,
        48,
        0,
        app.title_font,
    );
    control(
        hwnd,
        "STATIC",
        "手机与电脑，复制即同步。",
        0,
        34,
        76,
        540,
        30,
        0,
        f,
    );
    control(hwnd, "STATIC", "正在启动…", 0, 34, 118, 900, 46, STATUS, f);
    control(hwnd, "STATIC", "连接设置", 0, 34, 184, 480, 28, 0, f);
    control(
        hwnd,
        "STATIC",
        "局域网地址（自动获取）",
        0,
        34,
        222,
        500,
        26,
        0,
        f,
    );
    control(
        hwnd,
        "STATIC",
        &local_addresses().unwrap_or_default().join("、"),
        0,
        34,
        254,
        410,
        32,
        0,
        f,
    );
    control(hwnd, "STATIC", "监听端口", 0, 34, 302, 130, 26, 0, f);
    control(
        hwnd,
        "EDIT",
        &config.port.to_string(),
        WS_TABSTOP | ES_NUMBER as u32,
        174,
        296,
        120,
        32,
        PORT,
        f,
    );
    control(
        hwnd,
        "BUTTON",
        "允许公网来源连接",
        WS_TABSTOP | BS_AUTOCHECKBOX as u32,
        34,
        346,
        410,
        30,
        PUBLIC,
        f,
    );
    control(
        hwnd,
        "BUTTON",
        "启用蓝牙备用（重启程序生效）",
        WS_TABSTOP | BS_AUTOCHECKBOX as u32,
        34,
        384,
        440,
        30,
        BT,
        f,
    );
    control(
        hwnd,
        "BUTTON",
        "登录 Windows 后静默启动",
        WS_TABSTOP | BS_AUTOCHECKBOX as u32,
        34,
        422,
        410,
        30,
        STARTUP,
        f,
    );
    for (id, value) in [
        (PUBLIC, config.public_access),
        (BT, config.bluetooth),
        (STARTUP, config.auto_start),
    ] {
        SendDlgItemMessageW(
            hwnd,
            id,
            BM_SETCHECK,
            if value {
                BST_CHECKED as usize
            } else {
                BST_UNCHECKED as usize
            },
            0,
        );
    }
    control(
        hwnd,
        "BUTTON",
        "保存设置",
        WS_TABSTOP,
        34,
        474,
        124,
        38,
        SAVE,
        f,
    );
    control(
        hwnd,
        "BUTTON",
        "暂停同步",
        WS_TABSTOP,
        174,
        474,
        124,
        38,
        PAUSE,
        f,
    );
    control(hwnd, "STATIC", "安全配对", 0, 526, 184, 410, 28, 0, f);
    control(
        hwnd,
        "STATIC",
        "手机输入左侧地址和端口，核对校验码后确认。",
        0,
        526,
        222,
        420,
        48,
        0,
        f,
    );
    control(
        hwnd,
        "BUTTON",
        "开始配对（120 秒）",
        WS_TABSTOP,
        526,
        278,
        220,
        38,
        PAIR,
        f,
    );
    control(
        hwnd,
        "BUTTON",
        "关闭配对",
        WS_TABSTOP,
        762,
        278,
        140,
        38,
        CLOSE_PAIR,
        f,
    );
    control(hwnd, "STATIC", "配对未开启", 0, 526, 340, 410, 40, CODE, f);
    control(
        hwnd,
        "STATIC",
        "尚无已配对设备",
        0,
        34,
        548,
        440,
        102,
        PEERS,
        f,
    );
    control(
        hwnd,
        "BUTTON",
        "撤销全部设备",
        WS_TABSTOP,
        34,
        656,
        160,
        36,
        REVOKE,
        f,
    );
    control(
        hwnd,
        "STATIC",
        "仅同步文本 · 默认不保存历史 · 关闭窗口后继续在托盘运行",
        0,
        34,
        720,
        900,
        28,
        0,
        f,
    );
}

unsafe fn refresh(hwnd: HWND, app: &App) {
    let count = app.host.active.lock().unwrap().len();
    let state = if app.host.paused.load(Ordering::Relaxed) {
        "已暂停"
    } else {
        "同步已开启"
    };
    let status = format!(
        "{state}  ·  在线设备 {count}  ·  {}",
        app.host.status.lock().unwrap()
    );
    SetDlgItemTextW(hwnd, STATUS, wide(&status).as_ptr());
    let config = app.host.config.lock().unwrap();
    let peers = if config.peers.is_empty() {
        "尚无已配对设备".into()
    } else {
        format!(
            "已信任 {} 台设备：\r\n{}",
            config.peers.len(),
            config
                .peers
                .values()
                .cloned()
                .collect::<Vec<_>>()
                .join("、")
        )
    };
    SetDlgItemTextW(hwnd, PEERS, wide(&peers).as_ptr());
    SetDlgItemTextW(
        hwnd,
        PAUSE,
        wide(if app.host.paused.load(Ordering::Relaxed) {
            "恢复同步"
        } else {
            "暂停同步"
        })
        .as_ptr(),
    );
}

unsafe extern "system" fn window_proc(hwnd: HWND, message: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    let ptr = GetWindowLongPtrW(hwnd, GWLP_USERDATA) as *mut App;
    if ptr.is_null() {
        return DefWindowProcW(hwnd, message, w, l);
    }
    let app = &mut *ptr;
    if message == app.taskbar_created {
        notify_icon(hwnd, NIM_ADD);
        return 0;
    }
    match message {
        WM_CLOSE => {
            ShowWindow(hwnd, SW_HIDE);
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
                        clear_pairing(hwnd, app);
                        let message = format!(
                            "允许这台手机同步剪贴板？\n\n设备：{name}\n校验码：{}\n\n请确认手机上显示相同的校验码。",
                            &csync_core::protocol::text_hash(&format!("{}:{pin}", app.host.id))
                                [..12]
                        );
                        let yes = MessageBoxW(
                            hwnd,
                            wide(&message).as_ptr(),
                            wide("确认设备配对").as_ptr(),
                            MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2,
                        ) == IDYES;
                        let _ = reply.send(yes);
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
            refresh(hwnd, app);
            0
        }
        WM_TIMER => {
            if !app.host.trust.lock().unwrap().pairing_open() {
                clear_pairing(hwnd, app);
            }
            0
        }
        WM_COMMAND => {
            let id = (w & 0xffff) as i32;
            match id {
                SAVE => {
                    if let Err(e) = save_settings(hwnd, app) {
                        show_error(&e.to_string());
                    }
                }
                PAIR => {
                    if let Err(e) = begin_pairing(hwnd, app) {
                        show_error(&e.to_string());
                    }
                }
                CLOSE_PAIR => {
                    app.host.trust.lock().unwrap().close_pairing();
                    clear_pairing(hwnd, app);
                }
                PAUSE => {
                    app.host.paused.fetch_xor(true, Ordering::Relaxed);
                    refresh(hwnd, app);
                }
                REVOKE => {
                    if MessageBoxW(
                        hwnd,
                        wide("撤销所有设备？现有连接将断开，需要重新配对。").as_ptr(),
                        wide("CSync").as_ptr(),
                        MB_YESNO | MB_DEFBUTTON2,
                    ) == IDYES
                    {
                        if let Err(e) = app.host.revoke_all() {
                            show_error(&e.to_string());
                        }
                        clear_pairing(hwnd, app);
                        refresh(hwnd, app);
                    }
                }
                EXIT => PostQuitMessage(0),
                _ => {}
            }
            0
        }
        TRAY => {
            if l as u32 == WM_LBUTTONDBLCLK {
                ShowWindow(hwnd, SW_SHOW);
                SetForegroundWindow(hwnd);
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
                    ShowWindow(hwnd, SW_SHOW);
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

unsafe fn text(hwnd: HWND, id: i32) -> String {
    let child = GetDlgItem(hwnd, id);
    let len = GetWindowTextLengthW(child).min(4096) as usize;
    let mut buffer = vec![0u16; len + 1];
    GetWindowTextW(child, buffer.as_mut_ptr(), buffer.len() as i32);
    String::from_utf16_lossy(&buffer[..len])
}
unsafe fn checked(hwnd: HWND, id: i32) -> bool {
    SendDlgItemMessageW(hwnd, id, BM_GETCHECK, 0, 0) == BST_CHECKED as isize
}

unsafe fn save_settings(hwnd: HWND, app: &mut App) -> Result<()> {
    let mut config = app.host.config.lock().unwrap();
    let mut next = config.clone();
    next.port = text(hwnd, PORT).trim().parse()?;
    ensure!(next.port > 0, "端口范围为 1–65535");
    next.public_access = checked(hwnd, PUBLIC);
    next.bluetooth = checked(hwnd, BT);
    next.auto_start = checked(hwnd, STARTUP);
    let restart = next.port != config.port || next.bluetooth != config.bluetooth;
    set_startup(next.auto_start)?;
    app.host.store.save(&next)?;
    *config = next;
    drop(config);
    app.host.trust.lock().unwrap().close_pairing();
    clear_pairing(hwnd, app);
    app.host.status(if restart {
        "设置已保存；端口和蓝牙更改请退出后重新启动"
    } else {
        "设置已保存"
    });
    refresh(hwnd, app);
    Ok(())
}

unsafe fn begin_pairing(hwnd: HWND, app: &mut App) -> Result<()> {
    let config = app.host.config.lock().unwrap().clone();
    ensure!(
        config.port == app.running_port,
        "端口已更改，请先退出并重新启动 CSync 后再配对"
    );
    app.host.trust.lock().unwrap().open_pairing();
    SetDlgItemTextW(
        hwnd,
        CODE,
        wide("配对已开启，请在手机输入地址和端口（120 秒）").as_ptr(),
    );
    SetTimer(hwnd, 1, 1000, None);
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

unsafe fn clear_pairing(hwnd: HWND, app: &mut App) {
    let _ = app;
    KillTimer(hwnd, 1);
    SetDlgItemTextW(hwnd, CODE, wide("配对未开启").as_ptr());
}

unsafe fn notify_icon(hwnd: HWND, operation: u32) {
    let mut data: NOTIFYICONDATAW = std::mem::zeroed();
    data.cbSize = std::mem::size_of_val(&data) as u32;
    data.hWnd = hwnd;
    data.uID = 1;
    data.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    data.uCallbackMessage = TRAY;
    data.hIcon = LoadIconW(null_mut(), IDI_APPLICATION);
    let tip = wide("CSync · 剪贴板同步");
    data.szTip[..tip.len()].copy_from_slice(&tip);
    Shell_NotifyIconW(operation, &data);
}
