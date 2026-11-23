use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use parking_lot::RwLock;
use quinn::{ClientConfig, Endpoint, ServerConfig, TransportConfig};
use rcgen::{CertificateParams, KeyPair, PKCS_ED25519};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use salt_crdt::{DeltaPacket, VaultStore};
use salt_router::InboxEnrichmentOutput;
use salt_types::{now_utc_ms, sha256_hex, InboxKind, LanPeerInfo, SaltError, SaltResult};
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::path::Path;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub const MDNS_SERVICE_TYPE: &str = "_saltsync._tcp.local.";
pub const LAN_BEACON_PORT: u16 = 47822;
const PREFERRED_QUIC_PORT: u16 = 47821;
const MAX_FRAME_BYTES: usize = 128 * 1024 * 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct P2pSyncResult {
    pub peer_device_id: String,
    pub sent_packets: u32,
    pub received_packets: u32,
    pub sent_assets: u32,
    pub received_assets: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct AssetBlob {
    rel_path: String,
    bytes: Vec<u8>,
}

/// Phase-1 lightweight digest exchanged over QUIC before sending any payload bytes.
#[derive(Debug, Serialize, Deserialize)]
struct P2pDigest {
    device_id: String,
    page_vvs: BTreeMap<String, Vec<u8>>,
    asset_paths: Vec<String>,
}

/// Phase-2 incremental bundle containing ONLY missing Loro deltas/snapshots and missing assets.
#[derive(Debug, Serialize, Deserialize)]
struct P2pDeltaBundle {
    device_id: String,
    packets: Vec<DeltaPacket>,
    #[serde(default)]
    assets: Vec<AssetBlob>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct PersistedIdentity {
    device_id: String,
    cert_der: Vec<u8>,
    key_der: Vec<u8>,
    fingerprint: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RelayEnrichRequest {
    pub relay_request_id: String,
    pub kind: InboxKind,
    pub is_private: bool,
    pub text_note: Option<String>,
    pub media_bytes: Option<Vec<u8>>,
    pub media_ext: Option<String>,
    pub output_language: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RelayEnrichResponse {
    pub ok: bool,
    pub transcript_text: Option<String>,
    pub summary_text: Option<String>,
    #[serde(default)]
    pub auto_tags: Vec<String>,
    pub error_message: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(untagged)]
enum IncomingP2pEnvelope {
    Relay(RelayEnrichRequest),
    SyncDigest(P2pDigest),
}

#[derive(Debug, Serialize, Deserialize)]
struct LanBeaconPayload {
    magic: String,
    device_id: String,
    device_name: String,
    port: u16,
    fingerprint: String,
    #[serde(default)]
    relay_available: bool,
}

#[derive(Debug, Clone)]
struct TrackedPeer {
    info: LanPeerInfo,
    last_seen_ms: i64,
}

/// Callback invoked whenever incoming remote pages/assets are imported over LAN P2P.
pub type OnRemoteSyncHook = Arc<dyn Fn() + Send + Sync>;

/// Callback handling incoming LAN `SaltRelay` compute requests over pinned QUIC/TLS 1.3.
pub type SaltRelayHandler = Arc<
    dyn Fn(RelayEnrichRequest) -> Pin<Box<dyn Future<Output = SaltResult<InboxEnrichmentOutput>> + Send>>
        + Send
        + Sync,
>;

/// LAN P2P discovery & QUIC/TLS 1.3 direct full-vault (Loro deltas + WebP assets) sync + SaltRelay node.
pub struct LanP2pNode {
    device_id: String,
    cert_fingerprint: String,
    local_ip: String,
    local_port: u16,
    endpoint: Endpoint,
    mdns: Option<ServiceDaemon>,
    peers: Arc<RwLock<HashMap<String, TrackedPeer>>>,
    stop_flag: Arc<AtomicBool>,
    on_remote_sync: Option<OnRemoteSyncHook>,
}

impl LanP2pNode {
    /// Loads or generates a persistent self-signed Ed25519 identity certificate,
    /// binds a QUIC/TLS 1.3 server on an IPv4 socket, and optionally starts dual LAN discovery
    /// (mDNS `_saltsync._tcp.local.` + UDP broadcast beacon on port 47822).
    pub fn start(
        vault: Arc<VaultStore>,
        enable_discovery: bool,
        on_remote_sync: Option<OnRemoteSyncHook>,
    ) -> SaltResult<Self> {
        Self::start_with_relay(vault, enable_discovery, on_remote_sync, None)
    }

    pub fn start_with_relay(
        vault: Arc<VaultStore>,
        enable_discovery: bool,
        on_remote_sync: Option<OnRemoteSyncHook>,
        relay_handler: Option<SaltRelayHandler>,
    ) -> SaltResult<Self> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let dev = vault.device_state();
        let id_path = vault.paths().local_dir().join("p2p_identity.json");
        let (cert_der, key_der, fingerprint) = load_or_create_ed25519_cert(&id_path, &dev.device_id)?;

        let server_crypto = rustls::ServerConfig::builder()
            .with_no_client_auth()
            .with_single_cert(vec![cert_der], key_der)
            .map_err(|e| SaltError::Sync(format!("TLS server config failed: {e}")))?;

        let mut transport = TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));
        transport.keep_alive_interval(Some(Duration::from_secs(3)));

        let mut server_config = ServerConfig::with_crypto(Arc::new(
            quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto)
                .map_err(|e| SaltError::Sync(format!("QUIC server config failed: {e}")))?,
        ));
        server_config.transport_config(Arc::new(transport));

        // Prefer stable QUIC port 47821 for firewall friendliness, fallback to ephemeral port 0
        let endpoint = Endpoint::server(
            server_config.clone(),
            SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), PREFERRED_QUIC_PORT),
        )
        .or_else(|_| {
            Endpoint::server(
                server_config,
                SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0),
            )
        })
        .map_err(|e| SaltError::Sync(format!("QUIC bind failed: {e}")))?;

        let local_port = endpoint
            .local_addr()
            .map_err(|e| SaltError::Sync(e.to_string()))?
            .port();
        let local_ipv4 = detect_primary_lan_ipv4();
        let local_ip = local_ipv4.to_string();

        // Background QUIC accept loop for incoming LAN peer sync & SaltRelay
        let accept_ep = endpoint.clone();
        let accept_vault = Arc::clone(&vault);
        let accept_hook = on_remote_sync.clone();
        let accept_relay = relay_handler.clone();
        tokio::spawn(async move {
            while let Some(incoming) = accept_ep.accept().await {
                let v = Arc::clone(&accept_vault);
                let h = accept_hook.clone();
                let r = accept_relay.clone();
                tokio::spawn(async move {
                    if let Ok(conn) = incoming.await {
                        let _ = handle_incoming_sync_conn(conn, &v, h.as_ref(), r.as_ref()).await;
                    }
                });
            }
        });

        let peers: Arc<RwLock<HashMap<String, TrackedPeer>>> =
            Arc::new(RwLock::new(HashMap::new()));
        let stop_flag = Arc::new(AtomicBool::new(false));
        let advertise_relay = vault
            .load_router_config()
            .map(|c| c.salt_relay_enabled)
            .unwrap_or(false)
            && relay_handler.is_some();

        let mdns = if enable_discovery {
            // 1. Start reliable UDP LAN broadcast beacon (works even when mDNS multicast is dropped)
            start_udp_lan_beacon(
                dev.device_id.clone(),
                dev.device_name.clone(),
                local_ipv4,
                local_port,
                fingerprint.clone(),
                advertise_relay,
                Arc::clone(&peers),
                Arc::clone(&stop_flag),
            );

            // 2. Also register & browse standard mDNS `_saltsync._tcp.local.` pinned to real LAN IPv4
            match ServiceDaemon::new() {
                Ok(daemon) => {
                    let short = &dev.device_id[..8.min(dev.device_id.len())];
                    let instance_name = format!("saltnote-{short}");
                    let host_name = format!("{instance_name}.local.");
                    let mut props = HashMap::new();
                    props.insert("device_id".to_string(), dev.device_id.clone());
                    props.insert("device_name".to_string(), dev.device_name.clone());
                    props.insert("fingerprint".to_string(), fingerprint.clone());
                    props.insert("ipv4".to_string(), local_ip.clone());
                    props.insert(
                        "relay".to_string(),
                        if advertise_relay { "1" } else { "0" }.to_string(),
                    );

                    if let Ok(info) = ServiceInfo::new(
                        MDNS_SERVICE_TYPE,
                        &instance_name,
                        &host_name,
                        local_ip.as_str(),
                        local_port,
                        Some(props),
                    ) {
                        let _ = daemon.register(info);
                    }

                    if let Ok(receiver) = daemon.browse(MDNS_SERVICE_TYPE) {
                        let my_id = dev.device_id.clone();
                        let peers_ref = Arc::clone(&peers);
                        let stop = Arc::clone(&stop_flag);
                        std::thread::spawn(move || {
                            while !stop.load(Ordering::Relaxed) {
                                match receiver.recv_timeout(Duration::from_millis(500)) {
                                    Ok(ServiceEvent::ServiceResolved(info)) => {
                                        let peer_id = info
                                            .get_property_val_str("device_id")
                                            .unwrap_or_default()
                                            .to_string();
                                        if peer_id.is_empty() || peer_id == my_id {
                                            continue;
                                        }
                                        let peer_name = info
                                            .get_property_val_str("device_name")
                                            .unwrap_or("SaltNote Peer")
                                            .to_string();
                                        let fp = info
                                            .get_property_val_str("fingerprint")
                                            .unwrap_or_default()
                                            .to_string();

                                        // Prefer explicit `ipv4` TXT property, else best private IPv4 from addresses
                                        let best_host = info
                                            .get_property_val_str("ipv4")
                                            .filter(|s| s.parse::<Ipv4Addr>().is_ok() && *s != "127.0.0.1")
                                            .map(|s| s.to_string())
                                            .or_else(|| pick_best_lan_ip(info.get_addresses()));

                                        if let Some(host) = best_host {
                                            let relay_available = info
                                                .get_property_val_str("relay")
                                                .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                                                .unwrap_or(false);
                                            peers_ref.write().insert(
                                                peer_id.clone(),
                                                TrackedPeer {
                                                    info: LanPeerInfo {
                                                        device_id: peer_id,
                                                        device_name: peer_name,
                                                        host,
                                                        port: info.get_port(),
                                                        cert_fingerprint: fp,
                                                        relay_available,
                                                    },
                                                    last_seen_ms: now_utc_ms(),
                                                },
                                            );
                                        }
                                    }
                                    Ok(ServiceEvent::ServiceRemoved(_, fullname)) => {
                                        peers_ref.write().retain(|id, _| !fullname.contains(id));
                                    }
                                    Err(err) => {
                                        if err.to_string().to_ascii_lowercase().contains("disconnected") {
                                            break;
                                        }
                                    }
                                    _ => {}
                                }
                            }
                        });
                    }
                    Some(daemon)
                }
                Err(_) => None,
            }
        } else {
            None
        };

        Ok(Self {
            device_id: dev.device_id,
            cert_fingerprint: fingerprint,
            local_ip,
            local_port,
            endpoint,
            mdns,
            peers,
            stop_flag,
            on_remote_sync,
        })
    }

    pub fn local_ip(&self) -> &str {
        &self.local_ip
    }

    pub fn local_port(&self) -> u16 {
        self.local_port
    }

    pub fn cert_fingerprint(&self) -> &str {
        &self.cert_fingerprint
    }

    pub fn discovered_peers(&self) -> Vec<LanPeerInfo> {
        let now = now_utc_ms();
        let mut map = self.peers.write();
        map.retain(|_, v| now - v.last_seen_ms < 30_000);
        let mut list: Vec<LanPeerInfo> = map.values().map(|t| t.info.clone()).collect();
        list.sort_by(|a, b| a.device_name.cmp(&b.device_name).then(a.device_id.cmp(&b.device_id)));
        list
    }

    /// Connects to a LAN peer over QUIC/TLS 1.3 with pinned Ed25519 certificate verification,
    /// atomically exchanges ALL pages (Loro snapshots) AND ALL `assets/*.webp` blobs, and retries once on UDP drop.
    pub async fn sync_with_peer(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        vault: &VaultStore,
    ) -> SaltResult<P2pSyncResult> {
        let mut last_err = None;
        for attempt in 0..2 {
            match self
                .sync_with_peer_once(peer_addr, expected_fingerprint, vault)
                .await
            {
                Ok(res) => return Ok(res),
                Err(e) => {
                    let msg = e.to_string();
                    if msg.contains("fingerprint mismatch") || attempt == 1 {
                        return Err(e);
                    }
                    last_err = Some(e);
                    tokio::time::sleep(Duration::from_millis(350)).await;
                }
            }
        }
        Err(last_err.unwrap_or_else(|| SaltError::Sync("LAN sync failed".into())))
    }

    async fn connect_pinned_quic(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
    ) -> SaltResult<quinn::Connection> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let verifier = Arc::new(PinnedFingerprintVerifier {
            expected_sha256_hex: expected_fingerprint.trim().to_ascii_lowercase(),
        });
        let client_crypto = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_no_client_auth();

        let mut transport = TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));
        transport.keep_alive_interval(Some(Duration::from_secs(3)));

        let mut client_config = ClientConfig::new(Arc::new(
            quinn::crypto::rustls::QuicClientConfig::try_from(client_crypto)
                .map_err(|e| SaltError::Sync(e.to_string()))?,
        ));
        client_config.transport_config(Arc::new(transport));

        let connecting = self
            .endpoint
            .connect_with(client_config, peer_addr, "saltnote.local")
            .map_err(|e| SaltError::Sync(format!("QUIC connect failed: {e}")))?;

        tokio::time::timeout(Duration::from_secs(8), connecting)
            .await
            .map_err(|_| SaltError::Sync(format!("连接超时 ({peer_addr})，请确认双方在同一 Wi-Fi 且未被防火墙拦截")))?
            .map_err(|e| SaltError::Sync(format!("QUIC 握手失败: {e}")))
    }

    /// Sends a `#private` inbox item over pinned QUIC/TLS 1.3 to a paired LAN PC's SaltRelay service.
    pub async fn relay_enrich_with_peer(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        req: RelayEnrichRequest,
    ) -> SaltResult<InboxEnrichmentOutput> {
        let conn = self
            .connect_pinned_quic(peer_addr, expected_fingerprint)
            .await?;
        let (mut send, mut recv) = conn
            .open_bi()
            .await
            .map_err(|e| SaltError::Sync(e.to_string()))?;
        write_framed_json(&mut send, &req).await?;
        send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;

        let resp: RelayEnrichResponse = read_framed_json(&mut recv).await?;
        conn.close(0u32.into(), b"relay_done");
        if !resp.ok {
            return Err(SaltError::Network(
                resp.error_message
                    .unwrap_or_else(|| "SaltRelay failed on peer".into()),
            ));
        }
        Ok(InboxEnrichmentOutput {
            transcript_text: resp.transcript_text,
            summary_text: resp.summary_text,
            auto_tags: resp.auto_tags,
        })
    }

    async fn sync_with_peer_once(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        vault: &VaultStore,
    ) -> SaltResult<P2pSyncResult> {
        let conn = self
            .connect_pinned_quic(peer_addr, expected_fingerprint)
            .await?;

        let (mut send, mut recv) = conn
            .open_bi()
            .await
            .map_err(|e| SaltError::Sync(e.to_string()))?;

        // Phase 1: Exchange lightweight Loro VersionVector + asset path digests
        let _ = vault.flush_all_dirty_deltas();
        let local_assets_list = vault.list_asset_rel_paths()?;
        let local_digest = P2pDigest {
            device_id: self.device_id.clone(),
            page_vvs: vault.export_page_version_vectors(),
            asset_paths: local_assets_list.clone(),
        };
        write_framed_json(&mut send, &local_digest).await?;
        let peer_digest: P2pDigest = read_framed_json(&mut recv).await?;

        // Phase 2: Compute and send ONLY missing page deltas & missing assets
        let out_packets = vault.export_diff_packets_against(&peer_digest.page_vvs)?;
        let peer_asset_set: HashSet<&str> =
            peer_digest.asset_paths.iter().map(String::as_str).collect();
        let missing_asset_paths: Vec<String> = local_assets_list
            .into_iter()
            .filter(|p| !peer_asset_set.contains(p.as_str()))
            .collect();
        let out_assets: Vec<AssetBlob> = vault
            .read_assets_by_paths(&missing_asset_paths)?
            .into_iter()
            .map(|(rel_path, bytes)| AssetBlob { rel_path, bytes })
            .collect();

        let sent_packets = out_packets.len() as u32;
        let sent_assets = out_assets.len() as u32;
        let out_bundle = P2pDeltaBundle {
            device_id: self.device_id.clone(),
            packets: out_packets,
            assets: out_assets,
        };
        write_framed_json(&mut send, &out_bundle).await?;
        send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;

        let in_bundle: P2pDeltaBundle = read_framed_json(&mut recv).await?;

        // Import missing assets first, then missing page deltas
        let mut received_assets = 0u32;
        for asset in &in_bundle.assets {
            vault.store_asset_bytes(&asset.rel_path, &asset.bytes)?;
            received_assets += 1;
        }
        let mut received_packets = 0u32;
        for pkt in &in_bundle.packets {
            let raw = serde_json::to_vec(pkt).map_err(|e| SaltError::Sync(e.to_string()))?;
            vault.import_delta_packet_bytes(&raw)?;
            received_packets += 1;
        }

        if received_packets > 0 || received_assets > 0 {
            if let Some(hook) = &self.on_remote_sync {
                hook();
            }
        }

        conn.close(0u32.into(), b"done");
        Ok(P2pSyncResult {
            peer_device_id: in_bundle.device_id,
            sent_packets,
            received_packets,
            sent_assets,
            received_assets,
        })
    }

    pub fn shutdown(&self) {
        self.stop_flag.store(true, Ordering::SeqCst);
        if let Some(d) = &self.mdns {
            let _ = d.shutdown();
        }
        self.endpoint.close(0u32.into(), b"shutdown");
    }
}

async fn write_framed_json<T: Serialize>(send: &mut quinn::SendStream, value: &T) -> SaltResult<()> {
    let bytes = serde_json::to_vec(value).map_err(|e| SaltError::Sync(e.to_string()))?;
    if bytes.len() > MAX_FRAME_BYTES {
        return Err(SaltError::Sync("Sync frame exceeds size limit".into()));
    }
    let len_le = (bytes.len() as u32).to_le_bytes();
    send.write_all(&len_le)
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    send.write_all(&bytes)
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    Ok(())
}

async fn read_framed_json<T: for<'de> Deserialize<'de>>(recv: &mut quinn::RecvStream) -> SaltResult<T> {
    let mut len_buf = [0u8; 4];
    tokio::time::timeout(Duration::from_secs(30), recv.read_exact(&mut len_buf))
        .await
        .map_err(|_| SaltError::Sync("读取对端同步帧头超时".into()))?
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    let frame_len = u32::from_le_bytes(len_buf) as usize;
    if frame_len > MAX_FRAME_BYTES {
        return Err(SaltError::Sync("Incoming sync frame exceeds size limit".into()));
    }
    let mut buf = vec![0u8; frame_len];
    tokio::time::timeout(Duration::from_secs(30), recv.read_exact(&mut buf))
        .await
        .map_err(|_| SaltError::Sync("读取对端同步数据超时".into()))?
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    serde_json::from_slice(&buf).map_err(|e| SaltError::Sync(format!("Invalid sync frame: {e}")))
}

async fn handle_incoming_sync_conn(
    conn: quinn::Connection,
    vault: &VaultStore,
    on_remote_sync: Option<&OnRemoteSyncHook>,
    relay_handler: Option<&SaltRelayHandler>,
) -> SaltResult<()> {
    let (mut send, mut recv) = conn
        .accept_bi()
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))?;

    let envelope: IncomingP2pEnvelope = read_framed_json(&mut recv).await?;
    let peer_digest = match envelope {
        IncomingP2pEnvelope::Relay(relay_req) => {
            let reply = match relay_handler {
                Some(handler) => match handler(relay_req).await {
                    Ok(out) => RelayEnrichResponse {
                        ok: true,
                        transcript_text: out.transcript_text,
                        summary_text: out.summary_text,
                        auto_tags: out.auto_tags,
                        error_message: None,
                    },
                    Err(e) => RelayEnrichResponse {
                        ok: false,
                        transcript_text: None,
                        summary_text: None,
                        auto_tags: Vec::new(),
                        error_message: Some(e.to_string()),
                    },
                },
                None => RelayEnrichResponse {
                    ok: false,
                    transcript_text: None,
                    summary_text: None,
                    auto_tags: Vec::new(),
                    error_message: Some("SaltRelay is disabled on this PC node".into()),
                },
            };
            write_framed_json(&mut send, &reply).await?;
            send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;
            let _ = tokio::time::timeout(Duration::from_secs(10), conn.closed()).await;
            return Ok(());
        }
        IncomingP2pEnvelope::SyncDigest(digest) => digest,
    };

    // Phase 1: Reply with local digest BEFORE importing anything
    let _ = vault.flush_all_dirty_deltas();
    let local_assets_list = vault.list_asset_rel_paths()?;
    let local_digest = P2pDigest {
        device_id: vault.device_state().device_id,
        page_vvs: vault.export_page_version_vectors(),
        asset_paths: local_assets_list.clone(),
    };
    write_framed_json(&mut send, &local_digest).await?;

    // Compute diff for peer BEFORE importing incoming bundle (zero echo)
    let out_packets = vault.export_diff_packets_against(&peer_digest.page_vvs)?;
    let peer_asset_set: HashSet<&str> =
        peer_digest.asset_paths.iter().map(String::as_str).collect();
    let missing_asset_paths: Vec<String> = local_assets_list
        .into_iter()
        .filter(|p| !peer_asset_set.contains(p.as_str()))
        .collect();
    let out_assets: Vec<AssetBlob> = vault
        .read_assets_by_paths(&missing_asset_paths)?
        .into_iter()
        .map(|(rel_path, bytes)| AssetBlob { rel_path, bytes })
        .collect();

    // Phase 2: Receive peer's diff bundle, import locally, then send our diff bundle
    let in_bundle: P2pDeltaBundle = read_framed_json(&mut recv).await?;
    let mut imported_any = false;
    for asset in &in_bundle.assets {
        if vault.store_asset_bytes(&asset.rel_path, &asset.bytes).is_ok() {
            imported_any = true;
        }
    }
    for pkt in &in_bundle.packets {
        if let Ok(raw) = serde_json::to_vec(pkt) {
            if vault.import_delta_packet_bytes(&raw).is_ok() {
                imported_any = true;
            }
        }
    }

    if imported_any {
        if let Some(hook) = on_remote_sync {
            hook();
        }
    }

    let reply = P2pDeltaBundle {
        device_id: vault.device_state().device_id,
        packets: out_packets,
        assets: out_assets,
    };
    write_framed_json(&mut send, &reply).await?;
    send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;

    // Wait for client's clean close confirmation so the full reply stream is guaranteed delivered
    let _ = tokio::time::timeout(Duration::from_secs(10), conn.closed()).await;
    Ok(())
}

// ============================================================================
// Dual LAN Discovery Helpers (UDP Broadcast Beacon + Primary IPv4 Resolution)
// ============================================================================

fn start_udp_lan_beacon(
    device_id: String,
    device_name: String,
    local_ipv4: Ipv4Addr,
    quic_port: u16,
    fingerprint: String,
    relay_available: bool,
    peers: Arc<RwLock<HashMap<String, TrackedPeer>>>,
    stop: Arc<AtomicBool>,
) {
    // Listener thread on UDP 47822 (SO_REUSEADDR)
    let listen_id = device_id.clone();
    let listen_peers = Arc::clone(&peers);
    let listen_stop = Arc::clone(&stop);
    std::thread::spawn(move || {
        let Ok(sock) = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP)) else {
            return;
        };
        let _ = sock.set_reuse_address(true);
        let _ = sock.set_broadcast(true);
        let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), LAN_BEACON_PORT);
        if sock.bind(&addr.into()).is_err() {
            return;
        }
        let std_sock: UdpSocket = sock.into();
        let _ = std_sock.set_read_timeout(Some(Duration::from_millis(600)));

        let mut buf = [0u8; 2048];
        while !listen_stop.load(Ordering::Relaxed) {
            if let Ok((n, src)) = std_sock.recv_from(&mut buf) {
                if let Ok(beacon) = serde_json::from_slice::<LanBeaconPayload>(&buf[..n]) {
                    if beacon.magic != "SALTNOTE_LAN_V1" || beacon.device_id == listen_id {
                        continue;
                    }
                    // Use the actual IPv4 packet source address so reachability is guaranteed
                    if let IpAddr::V4(v4) = src.ip() {
                        if v4.is_loopback() || v4.is_unspecified() {
                            continue;
                        }
                        listen_peers.write().insert(
                            beacon.device_id.clone(),
                            TrackedPeer {
                                info: LanPeerInfo {
                                    device_id: beacon.device_id,
                                    device_name: beacon.device_name,
                                    host: v4.to_string(),
                                    port: beacon.port,
                                    cert_fingerprint: beacon.fingerprint,
                                    relay_available: beacon.relay_available,
                                },
                                last_seen_ms: now_utc_ms(),
                            },
                        );
                    }
                }
            }
        }
    });

    // Broadcaster thread sending lightweight LAN presence every 2 seconds
    let broadcast_stop = Arc::clone(&stop);
    std::thread::spawn(move || {
        let Ok(sock) = UdpSocket::bind(SocketAddr::new(IpAddr::V4(local_ipv4), 0))
            .or_else(|_| UdpSocket::bind("0.0.0.0:0"))
        else {
            return;
        };
        let _ = sock.set_broadcast(true);
        let payload = LanBeaconPayload {
            magic: "SALTNOTE_LAN_V1".into(),
            device_id,
            device_name,
            port: quic_port,
            fingerprint,
            relay_available,
        };
        let Ok(bytes) = serde_json::to_vec(&payload) else {
            return;
        };
        let octets = local_ipv4.octets();
        let subnet_bcast = SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(octets[0], octets[1], octets[2], 255)),
            LAN_BEACON_PORT,
        );
        let global_bcast = SocketAddr::new(IpAddr::V4(Ipv4Addr::BROADCAST), LAN_BEACON_PORT);

        while !broadcast_stop.load(Ordering::Relaxed) {
            let _ = sock.send_to(&bytes, global_bcast);
            if !local_ipv4.is_loopback() {
                let _ = sock.send_to(&bytes, subnet_bcast);
            }
            for _ in 0..10 {
                if broadcast_stop.load(Ordering::Relaxed) {
                    return;
                }
                std::thread::sleep(Duration::from_millis(200));
            }
        }
    });
}

/// Zero-packet kernel routing table lookup for this machine's active LAN IPv4 address.
pub fn detect_primary_lan_ipv4() -> Ipv4Addr {
    // UDP connect() does NOT transmit any packet; it purely resolves the outbound NIC IPv4
    for probe in ["192.168.1.1:80", "10.0.0.1:80", "192.0.2.1:80"] {
        if let Ok(sock) = UdpSocket::bind("0.0.0.0:0") {
            if sock.connect(probe).is_ok() {
                if let Ok(SocketAddr::V4(local)) = sock.local_addr() {
                    let ip = *local.ip();
                    if !ip.is_loopback() && !ip.is_unspecified() && !ip.is_link_local() {
                        return ip;
                    }
                }
            }
        }
    }
    Ipv4Addr::LOCALHOST
}

fn pick_best_lan_ip(addrs: &std::collections::HashSet<IpAddr>) -> Option<String> {
    let mut scored: Vec<(u8, String)> = Vec::new();
    for ip in addrs {
        match ip {
            IpAddr::V4(v4) => {
                if v4.is_loopback() || v4.is_link_local() || v4.is_unspecified() {
                    continue;
                }
                let oct = v4.octets();
                let score = if oct[0] == 192 && oct[1] == 168 {
                    100
                } else if oct[0] == 10 {
                    90
                } else if oct[0] == 172 && (16..=31).contains(&oct[1]) {
                    70 // Lower than 192.168 / 10.x to avoid WSL/Docker virtual bridges
                } else {
                    50
                };
                scored.push((score, v4.to_string()));
            }
            IpAddr::V6(_) => {
                // Skip IPv6 link-local `fe80::` without scope id since QUIC cannot dial it reliably
            }
        }
    }
    scored.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    scored.into_iter().next().map(|(_, ip)| ip)
}

fn load_or_create_ed25519_cert(
    path: &Path,
    device_id: &str,
) -> SaltResult<(CertificateDer<'static>, PrivateKeyDer<'static>, String)> {
    if let Ok(raw) = std::fs::read_to_string(path) {
        if let Ok(saved) = serde_json::from_str::<PersistedIdentity>(&raw) {
            if saved.device_id == device_id && !saved.cert_der.is_empty() && !saved.key_der.is_empty() {
                return Ok((
                    CertificateDer::from(saved.cert_der),
                    PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(saved.key_der)),
                    saved.fingerprint,
                ));
            }
        }
    }

    let key_pair = KeyPair::generate_for(&PKCS_ED25519)
        .map_err(|e| SaltError::Sync(format!("Ed25519 keygen failed: {e}")))?;
    let mut params = CertificateParams::new(vec!["saltnote.local".into(), device_id.to_string()])
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    params
        .distinguished_name
        .push(rcgen::DnType::CommonName, device_id);
    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| SaltError::Sync(e.to_string()))?;

    let cert_bytes = cert.der().to_vec();
    let key_bytes = key_pair.serialize_der();
    let fp = sha256_hex(&cert_bytes);

    let saved = PersistedIdentity {
        device_id: device_id.to_string(),
        cert_der: cert_bytes.clone(),
        key_der: key_bytes.clone(),
        fingerprint: fp.clone(),
    };
    if let Ok(json) = serde_json::to_string_pretty(&saved) {
        let _ = std::fs::write(path, json);
    }

    Ok((
        CertificateDer::from(cert_bytes),
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(key_bytes)),
        fp,
    ))
}

#[derive(Debug)]
struct PinnedFingerprintVerifier {
    expected_sha256_hex: String,
}

impl ServerCertVerifier for PinnedFingerprintVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        let actual = sha256_hex(end_entity.as_ref());
        if actual == self.expected_sha256_hex {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General(format!(
                "Peer Ed25519 certificate fingerprint mismatch: expected {}, got {}",
                self.expected_sha256_hex, actual
            )))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(
            message,
            cert,
            dss,
            &rustls::crypto::ring::default_provider().signature_verification_algorithms,
        )
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider()
            .signature_verification_algorithms
            .supported_schemes()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use salt_types::{new_ulid, PageMode};
    use std::sync::atomic::AtomicUsize;

    #[tokio::test]
    async fn lan_quic_ed25519_pinned_sync_converges_all_pages_and_assets() {
        let base = std::env::temp_dir().join(format!("saltnote_p2p_{}", new_ulid()));
        let v1 = Arc::new(VaultStore::open(base.join("tablet"), Some("Tablet".into())).unwrap());
        let v2 = Arc::new(VaultStore::open(base.join("desktop"), Some("Desktop".into())).unwrap());

        let m1a = v1
            .create_page("Tablet Note A", PageMode::Journal, false, vec![], "Sketch A")
            .unwrap();
        let m1b = v1
            .create_page("Tablet Note B", PageMode::Study, false, vec![], "Notes B")
            .unwrap();
        v1.store_asset_bytes("assets/ab/abcdef0123456789.webp", b"RIFFfakewebpdata")
            .unwrap();

        let m2 = v2
            .create_page("Desktop Note", PageMode::Study, false, vec![], "Desktop lecture note")
            .unwrap();

        let receiver_notified = Arc::new(AtomicUsize::new(0));
        let rn = Arc::clone(&receiver_notified);
        let node1 = LanP2pNode::start(Arc::clone(&v1), false, None).unwrap();
        let node2 = LanP2pNode::start(
            Arc::clone(&v2),
            false,
            Some(Arc::new(move || {
                rn.fetch_add(1, Ordering::SeqCst);
            })),
        )
        .unwrap();

        let addr2 = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), node2.local_port());
        let res = node1
            .sync_with_peer(addr2, node2.cert_fingerprint(), &v1)
            .await
            .unwrap();
        assert_eq!(res.sent_packets, 2);
        assert_eq!(res.sent_assets, 1);
        assert_eq!(res.received_packets, 1);
        assert!(receiver_notified.load(Ordering::SeqCst) >= 1);

        // Verify ALL pages AND image assets arrived cleanly on BOTH sides
        assert!(v1.get_page_snapshot(&m2.page_id).is_ok());
        assert!(v2.get_page_snapshot(&m1a.page_id).is_ok());
        assert!(v2.get_page_snapshot(&m1b.page_id).is_ok());
        assert!(v2.paths().root.join("assets/ab/abcdef0123456789.webp").exists());

        // Second sync with zero changes must transfer ZERO packets and ZERO assets (State Vector diff!)
        let res_noop = node1
            .sync_with_peer(addr2, node2.cert_fingerprint(), &v1)
            .await
            .unwrap();
        assert_eq!(res_noop.sent_packets, 0);
        assert_eq!(res_noop.received_packets, 0);
        assert_eq!(res_noop.sent_assets, 0);
        assert_eq!(res_noop.received_assets, 0);

        // Edit ONLY 1 page on tablet -> next sync transfers ONLY that 1 page delta and ZERO assets
        v1.update_page_body(&m1a.page_id, "Sketch A updated incrementally")
            .unwrap();
        let res_inc = node1
            .sync_with_peer(addr2, node2.cert_fingerprint(), &v1)
            .await
            .unwrap();
        assert_eq!(res_inc.sent_packets, 1);
        assert_eq!(res_inc.received_packets, 0);
        assert_eq!(res_inc.sent_assets, 0);
        assert_eq!(res_inc.received_assets, 0);
        assert_eq!(
            v2.get_page_snapshot(&m1a.page_id).unwrap().body_markdown,
            "Sketch A updated incrementally"
        );

        // Delete 1 page on desktop -> propagates tombstone to tablet cleanly
        v2.delete_page(&m1b.page_id).unwrap();
        let res_del = node1
            .sync_with_peer(addr2, node2.cert_fingerprint(), &v1)
            .await
            .unwrap();
        assert_eq!(res_del.received_packets, 1);
        assert!(v1.list_pages().iter().all(|p| p.meta.page_id != m1b.page_id));

        // Fingerprint persistence check across restarts
        let saved_fp = node2.cert_fingerprint().to_string();
        node2.shutdown();
        let node2_restarted = LanP2pNode::start(Arc::clone(&v2), false, None).unwrap();
        assert_eq!(node2_restarted.cert_fingerprint(), saved_fp);

        // Wrong fingerprint MUST be rejected!
        let addr2_new = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), node2_restarted.local_port());
        let bad = node1
            .sync_with_peer(addr2_new, "00000000000000000000000000000000", &v1)
            .await;
        assert!(bad.is_err());

        node1.shutdown();
        node2_restarted.shutdown();
        let _ = std::fs::remove_dir_all(&base);
    }

    #[tokio::test]
    async fn lan_p2p_syncs_inbox_and_executes_private_salt_relay() {
        let base = std::env::temp_dir().join(format!("saltnote_p2p_relay_{}", new_ulid()));
        let phone = Arc::new(VaultStore::open(base.join("phone"), Some("Phone".into())).unwrap());
        let pc = Arc::new(VaultStore::open(base.join("pc"), Some("PC".into())).unwrap());

        let item = phone
            .capture_inbox_item(
                InboxKind::VoiceMemo,
                None,
                Some(b"OGG_OPUS_BYTES"),
                Some("ogg"),
                None,
                5000,
                true,
            )
            .unwrap();

        let relay_handler: SaltRelayHandler = Arc::new(|req| {
            Box::pin(async move {
                assert!(req.is_private);
                assert_eq!(req.kind, InboxKind::VoiceMemo);
                Ok(InboxEnrichmentOutput {
                    transcript_text: Some("私密语音本地转写成功".into()),
                    summary_text: Some("本地摘要".into()),
                    auto_tags: vec!["私密".into()],
                })
            })
        });

        let phone_node = LanP2pNode::start(Arc::clone(&phone), false, None).unwrap();
        let pc_node =
            LanP2pNode::start_with_relay(Arc::clone(&pc), false, None, Some(relay_handler))
                .unwrap();

        let pc_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), pc_node.local_port());
        // 1. P2P sync transfers Vault-root InboxItem & audio asset to PC
        let sync_res = phone_node
            .sync_with_peer(pc_addr, pc_node.cert_fingerprint(), &phone)
            .await
            .unwrap();
        assert_eq!(sync_res.sent_packets, 1);
        assert_eq!(sync_res.sent_assets, 1);
        assert_eq!(pc.list_inbox_items(false, 10).len(), 1);

        // 2. SaltRelay over pinned QUIC returns local transcription without public internet
        let relay_out = phone_node
            .relay_enrich_with_peer(
                pc_addr,
                pc_node.cert_fingerprint(),
                RelayEnrichRequest {
                    relay_request_id: new_ulid(),
                    kind: item.kind,
                    is_private: true,
                    text_note: None,
                    media_bytes: Some(b"OGG_OPUS_BYTES".to_vec()),
                    media_ext: Some("ogg".into()),
                    output_language: Some("zh-CN".into()),
                },
            )
            .await
            .unwrap();
        assert_eq!(
            relay_out.transcript_text.as_deref(),
            Some("私密语音本地转写成功")
        );

        phone_node.shutdown();
        pc_node.shutdown();
        let _ = std::fs::remove_dir_all(&base);
    }
}
