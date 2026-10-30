//! Windows RFCOMM sockets are bridged to a bounded async byte stream. TLS and
//! the application protocol are identical to TCP. No local TCP proxy is exposed.
use crate::{
    server::{Host, connection},
    storage::wide,
};
use anyhow::{Result, ensure};
use std::{
    io::{Read, Write},
    os::windows::io::{FromRawSocket, RawSocket},
    sync::Arc,
    time::Duration,
};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use windows_sys::{
    Win32::{Devices::Bluetooth::*, Networking::WinSock::*},
    core::GUID,
};

const SERVICE_ID: GUID = GUID {
    data1: 0xb5d37a72,
    data2: 0x0b98,
    data3: 0x4bca,
    data4: [0x96, 0x90, 0xf1, 0xf7, 0x09, 0xd0, 0x93, 0xd8],
};

pub fn start(host: Arc<Host>, runtime: tokio::runtime::Handle) {
    std::thread::spawn(move || {
        if let Err(error) = listen(host.clone(), runtime) {
            host.ui.send(crate::server::UiEvent::Status(format!(
                "蓝牙服务不可用：{error}"
            )));
        }
    });
}

fn listen(host: Arc<Host>, runtime: tokio::runtime::Handle) -> Result<()> {
    unsafe {
        let mut data = std::mem::zeroed();
        ensure!(
            WSAStartup(0x0202, &mut data) == 0,
            "Winsock initialization failed"
        );
        let _winsock = WinsockGuard;
        let socket = socket(AF_BTH as i32, SOCK_STREAM, BTHPROTO_RFCOMM as i32);
        ensure!(
            socket != INVALID_SOCKET,
            "Bluetooth adapter unavailable ({})",
            WSAGetLastError()
        );
        let listener = SocketGuard(socket);
        let enabled = 1u32;
        ensure!(
            setsockopt(
                socket,
                SOL_RFCOMM as i32,
                SO_BTH_AUTHENTICATE as i32,
                (&enabled as *const u32).cast(),
                4
            ) == 0,
            "Bluetooth authentication unavailable"
        );
        ensure!(
            setsockopt(
                socket,
                SOL_RFCOMM as i32,
                SO_BTH_ENCRYPT as i32,
                (&enabled as *const u32).cast(),
                4
            ) == 0,
            "Bluetooth encryption unavailable"
        );
        let mut addr = SOCKADDR_BTH {
            addressFamily: AF_BTH,
            btAddr: 0,
            serviceClassId: std::mem::zeroed(),
            port: u32::MAX,
        };
        let mut len = std::mem::size_of_val(&addr) as i32;
        ensure!(
            bind(socket, (&addr as *const SOCKADDR_BTH).cast(), len) == 0,
            "cannot bind Bluetooth ({})",
            WSAGetLastError()
        );
        ensure!(
            getsockname(socket, (&mut addr as *mut SOCKADDR_BTH).cast(), &mut len) == 0,
            "cannot determine RFCOMM channel"
        );
        ensure!(
            windows_sys::Win32::Networking::WinSock::listen(socket, 4) == 0,
            "cannot listen on Bluetooth"
        );
        let mut service_id = SERVICE_ID;
        let mut name = wide("CSync Clipboard");
        let mut info: CSADDR_INFO = std::mem::zeroed();
        info.LocalAddr.lpSockaddr = (&mut addr as *mut SOCKADDR_BTH).cast();
        info.LocalAddr.iSockaddrLength = len;
        info.RemoteAddr.lpSockaddr = (&mut addr as *mut SOCKADDR_BTH).cast();
        info.RemoteAddr.iSockaddrLength = len;
        info.iSocketType = SOCK_STREAM;
        info.iProtocol = BTHPROTO_RFCOMM as i32;
        let mut query: WSAQUERYSETW = std::mem::zeroed();
        query.dwSize = std::mem::size_of_val(&query) as u32;
        query.lpszServiceInstanceName = name.as_mut_ptr();
        query.lpServiceClassId = &mut service_id;
        query.dwNameSpace = NS_BTH;
        query.dwNumberOfCsAddrs = 1;
        query.lpcsaBuffer = &mut info;
        ensure!(
            WSASetServiceW(&query, RNRSERVICE_REGISTER, 0) == 0,
            "Bluetooth SDP registration failed ({})",
            WSAGetLastError()
        );
        let _registration = SdpGuard(&query);
        host.ui
            .send(crate::server::UiEvent::Status("蓝牙备用服务已就绪".into()));
        loop {
            let client = accept(listener.0, std::ptr::null_mut(), std::ptr::null_mut());
            if client == INVALID_SOCKET {
                break;
            }
            let Ok(permit) = host.slots.clone().try_acquire_owned() else {
                closesocket(client);
                continue;
            };
            // TcpStream's Read/Write use Winsock send/recv, which also support RFCOMM.
            let mut input = std::net::TcpStream::from_raw_socket(client as RawSocket);
            // Provider support for generic socket timeouts varies. The async
            // TLS/application session also enforces its own deadlines and shuts
            // down the underlying socket when that session ends.
            let _ = input.set_read_timeout(Some(Duration::from_secs(120)));
            let _ = input.set_write_timeout(Some(Duration::from_secs(15)));
            let mut output = input.try_clone()?;
            let shutdown = input.try_clone()?;
            let (stream, bridge) = tokio::io::duplex(64 * 1024);
            let (mut rd, mut wr) = tokio::io::split(bridge);
            let rt = runtime.clone();
            std::thread::spawn(move || {
                let mut buffer = [0u8; 8192];
                loop {
                    let Ok(n) = input.read(&mut buffer) else {
                        break;
                    };
                    if n == 0 || rt.block_on(wr.write_all(&buffer[..n])).is_err() {
                        break;
                    }
                }
            });
            let rt = runtime.clone();
            std::thread::spawn(move || {
                let mut buffer = [0u8; 8192];
                loop {
                    let Ok(n) = rt.block_on(rd.read(&mut buffer)) else {
                        break;
                    };
                    if n == 0 || output.write_all(&buffer[..n]).is_err() {
                        break;
                    }
                }
            });
            let host = host.clone();
            runtime.spawn(async move {
                let _permit = permit;
                let _ = connection(host, stream, true).await;
                let _ = shutdown.shutdown(std::net::Shutdown::Both);
            });
        }
    }
    Ok(())
}
struct SocketGuard(SOCKET);
impl Drop for SocketGuard {
    fn drop(&mut self) {
        unsafe {
            closesocket(self.0);
        }
    }
}

struct WinsockGuard;
impl Drop for WinsockGuard {
    fn drop(&mut self) {
        unsafe {
            WSACleanup();
        }
    }
}
struct SdpGuard(*const WSAQUERYSETW);
impl Drop for SdpGuard {
    fn drop(&mut self) {
        unsafe {
            WSASetServiceW(self.0, RNRSERVICE_DELETE, 0);
        }
    }
}
