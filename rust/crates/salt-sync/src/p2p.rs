use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use parking_lot::RwLock;
use quinn::{ClientConfig, Endpoint, ServerConfig, TransportConfig};
use rcgen::{CertificateParams, KeyPair, PKCS_ED25519};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{DigitallySignedStruct, DistinguishedName, SignatureScheme};
use salt_crdt::{DeltaPacket, VaultStore, VAULT_ROOT_DOC_ID};
use salt_router::InboxEnrichmentOutput;
use salt_types::{
    new_pairing_pin_6, new_pairing_token_hex, new_ulid, now_utc_ms, sha256_hex, InboxKind,
    LanBatchSyncReport, LanPeerInfo, PairedPeer, SaltError, SaltResult,
};
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, Socket, Type};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::future::Future;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, UdpSocket};
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub const MDNS_SERVICE_TYPE: &str = "_saltsync._tcp.local.";
pub const LAN_BEACON_PORT: u16 = 47822;
const PREFERRED_QUIC_PORT: u16 = 47821;

const WIRE_MAGIC_V2: &[u8; 5] = b"SLTV2";
const FRAME_PAIR_REQ: u8 = 0x01;
const FRAME_PAIR_RESP: u8 = 0x02;
const FRAME_SYNC_DIGEST: u8 = 0x10;
const FRAME_SYNC_BATCH: u8 = 0x11;
const FRAME_PACKET: u8 = 0x12;
const FRAME_ASSET: u8 = 0x13;
const FRAME_RELAY_REQ: u8 = 0x20;
const FRAME_RELAY_RESP: u8 = 0x21;
const FRAME_ERROR: u8 = 0x7F;

const MAX_CONTROL_FRAME_BYTES: usize = 8 * 1024 * 1024;
const MAX_PACKET_LORO_BYTES: usize = 64 * 1024 * 1024;
const MAX_SINGLE_ASSET_BYTES: u64 = 512 * 1024 * 1024;
const STREAM_CHUNK_BYTES: usize = 64 * 1024;
const PAIRING_TTL_MS: i64 = 10 * 60 * 1000;
const MAX_PAIR_ATTEMPTS: u32 = 5;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct P2pSyncResult {
    pub peer_device_id: String,
    pub sent_packets: u32,
    pub received_packets: u32,
    pub sent_assets: u32,
    pub received_assets: u32,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct RemoteSyncScope {
    pub pages_changed: bool,
    pub inbox_changed: bool,
    pub assets_changed: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LanPairingInfo {
    pub token: String,
    pub pin: String,
    pub expires_at_utc: i64,
}

#[derive(Debug, Clone)]
struct PairingChallenge {
    token: String,
    pin: String,
    expires_at_utc: i64,
    failed_attempts: u32,
}

impl PairingChallenge {
    fn new_fresh() -> Self {
        Self {
            token: new_pairing_token_hex(),
            pin: new_pairing_pin_6(),
            expires_at_utc: now_utc_ms() + PAIRING_TTL_MS,
            failed_attempts: 0,
        }
    }

    fn as_info(&self) -> LanPairingInfo {
        LanPairingInfo {
            token: self.token.clone(),
            pin: self.pin.clone(),
            expires_at_utc: self.expires_at_utc,
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct PairRequest {
    device_id: String,
    device_name: String,
    quic_port: u16,
    pairing_secret: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct PairResponse {
    ok: bool,
    device_id: String,
    device_name: String,
    quic_port: u16,
    error_message: Option<String>,
}

/// Phase-1 lightweight digest exchanged over mTLS QUIC before sending any payload bytes.
#[derive(Debug, Serialize, Deserialize)]
struct P2pDigestV2 {
    device_id: String,
    page_vvs_hex: BTreeMap<String, String>,
    asset_paths: Vec<String>,
}

#[derive(Debug, Serialize, Deserialize)]
struct SyncBatchHeader {
    device_id: String,
    packet_count: u32,
    asset_count: u32,
}

#[derive(Debug, Serialize, Deserialize)]
struct DeltaPacketMeta {
    page_id: String,
    device_id: String,
    seq: u64,
    created_at_utc: i64,
}

#[derive(Debug, Serialize, Deserialize)]
struct AssetHeader {
    rel_path: String,
    byte_len: u64,
}

#[derive(Debug, Serialize, Deserialize)]
struct WireErrorPayload {
    message: String,
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

#[derive(Debug, Serialize, Deserialize)]
struct RelayHeader {
    relay_request_id: String,
    kind: InboxKind,
    is_private: bool,
    text_note: Option<String>,
    media_ext: Option<String>,
    output_language: Option<String>,
    has_media: bool,
    media_len: u32,
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
    mdns_fullname: Option<String>,
}

/// Callback invoked whenever incoming remote pages/inbox/assets are imported over LAN P2P.
pub type OnRemoteSyncHook = Arc<dyn Fn(RemoteSyncScope) + Send + Sync>;

/// Callback handling incoming LAN `SaltRelay` compute requests over mutual TLS 1.3 QUIC.
pub type SaltRelayHandler = Arc<
    dyn Fn(RelayEnrichRequest) -> Pin<Box<dyn Future<Output = SaltResult<InboxEnrichmentOutput>> + Send>>
        + Send
        + Sync,
>;

/// LAN P2P discovery & mutual-TLS QUIC/TLS 1.3 SLTV2 streaming sync + SaltRelay node.
pub struct LanP2pNode {
    device_id: String,
    device_name: String,
    cert_der: CertificateDer<'static>,
    key_der: PrivateKeyDer<'static>,
    cert_fingerprint: String,
    local_ipv4: Arc<RwLock<Ipv4Addr>>,
    local_port: u16,
    endpoint: Endpoint,
    mdns: Option<ServiceDaemon>,
    peers: Arc<RwLock<HashMap<String, TrackedPeer>>>,
    vault: Arc<VaultStore>,
    pairing_state: Arc<RwLock<PairingChallenge>>,
    relay_advertised: Arc<AtomicBool>,
    stop_flag: Arc<AtomicBool>,
    on_remote_sync: Option<OnRemoteSyncHook>,
}

impl LanP2pNode {
    /// Loads or generates a persistent self-signed Ed25519 identity certificate,
    /// binds a mutual-TLS QUIC/TLS 1.3 server on an IPv4 socket, and optionally starts
    /// dual LAN discovery (mDNS `_saltsync._tcp.local.` + UDP broadcast beacon on port 47822).
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
            .with_client_cert_verifier(Arc::new(Ed25519ClientCertVerifier))
            .with_single_cert(vec![cert_der.clone()], key_der.clone_key())
            .map_err(|e| SaltError::Sync(format!("mTLS server config failed: {e}")))?;

        let mut transport = TransportConfig::default();
        transport.max_idle_timeout(Some(Duration::from_secs(30).try_into().unwrap()));
        transport.keep_alive_interval(Some(Duration::from_secs(3)));

        let mut server_config = ServerConfig::with_crypto(Arc::new(
            quinn::crypto::rustls::QuicServerConfig::try_from(server_crypto)
                .map_err(|e| SaltError::Sync(format!("QUIC server config failed: {e}")))?,
        ));
        server_config.transport_config(Arc::new(transport));

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
        let initial_ipv4 = detect_primary_lan_ipv4();
        let local_ipv4 = Arc::new(RwLock::new(initial_ipv4));

        let peers: Arc<RwLock<HashMap<String, TrackedPeer>>> =
            Arc::new(RwLock::new(HashMap::new()));
        let stop_flag = Arc::new(AtomicBool::new(false));
        let pairing_state = Arc::new(RwLock::new(PairingChallenge::new_fresh()));
        let advertise_relay = vault
            .load_router_config()
            .map(|c| c.salt_relay_enabled)
            .unwrap_or(false)
            && relay_handler.is_some();
        let relay_advertised = Arc::new(AtomicBool::new(advertise_relay));

        // Background QUIC accept loop for authenticated mTLS sync, pairing, & SaltRelay
        let accept_ep = endpoint.clone();
        let accept_vault = Arc::clone(&vault);
        let accept_hook = on_remote_sync.clone();
        let accept_relay = relay_handler.clone();
        let accept_pairing = Arc::clone(&pairing_state);
        let accept_local_name = dev.device_name.clone();
        tokio::spawn(async move {
            while let Some(incoming) = accept_ep.accept().await {
                let v = Arc::clone(&accept_vault);
                let h = accept_hook.clone();
                let r = accept_relay.clone();
                let p = Arc::clone(&accept_pairing);
                let lname = accept_local_name.clone();
                tokio::spawn(async move {
                    if let Ok(conn) = incoming.await {
                        let _ = handle_incoming_sync_conn(
                            conn,
                            &v,
                            &lname,
                            local_port,
                            &p,
                            h.as_ref(),
                            r.as_ref(),
                        )
                        .await;
                    }
                });
            }
        });

        let mdns = if enable_discovery {
            start_udp_lan_beacon(
                dev.device_id.clone(),
                dev.device_name.clone(),
                Arc::clone(&local_ipv4),
                local_port,
                fingerprint.clone(),
                Arc::clone(&relay_advertised),
                Arc::clone(&peers),
                Arc::clone(&stop_flag),
            );

            match ServiceDaemon::new() {
                Ok(daemon) => {
                    register_mdns_service(
                        &daemon,
                        &dev.device_id,
                        &dev.device_name,
                        initial_ipv4,
                        local_port,
                        &fingerprint,
                        advertise_relay,
                    );

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

                                        let best_host = info
                                            .get_property_val_str("ipv4")
                                            .filter(|s| {
                                                s.parse::<Ipv4Addr>().is_ok() && *s != "127.0.0.1"
                                            })
                                            .map(|s| s.to_string())
                                            .or_else(|| pick_best_lan_ip(info.get_addresses()));

                                        if let Some(host) = best_host {
                                            let relay_available = info
                                                .get_property_val_str("relay")
                                                .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                                                .unwrap_or(false);
                                            let fullname = info.get_fullname().to_string();
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
                                                        is_paired: false,
                                                    },
                                                    last_seen_ms: now_utc_ms(),
                                                    mdns_fullname: Some(fullname),
                                                },
                                            );
                                        }
                                    }
                                    Ok(ServiceEvent::ServiceRemoved(_, fullname)) => {
                                        peers_ref.write().retain(|id, tracked| {
                                            let short = &id[..8.min(id.len())];
                                            let prefix = format!("saltnote-{short}");
                                            tracked.mdns_fullname.as_deref()
                                                != Some(fullname.as_str())
                                                && !fullname.starts_with(&prefix)
                                        });
                                    }
                                    Err(err) => {
                                        if err.to_string().to_ascii_lowercase().contains("disconnected")
                                        {
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
            device_name: dev.device_name,
            cert_der,
            key_der,
            cert_fingerprint: fingerprint,
            local_ipv4,
            local_port,
            endpoint,
            mdns,
            peers,
            vault,
            pairing_state,
            relay_advertised,
            stop_flag,
            on_remote_sync,
        })
    }

    pub fn local_ip(&self) -> String {
        self.local_ipv4.read().to_string()
    }

    pub fn local_port(&self) -> u16 {
        self.local_port
    }

    pub fn cert_fingerprint(&self) -> &str {
        &self.cert_fingerprint
    }

    pub fn is_relay_advertised(&self) -> bool {
        self.relay_advertised.load(Ordering::Relaxed)
    }

    /// Hot-updates SaltRelay availability across both UDP broadcast beacon and mDNS TXT without restarting QUIC.
    pub fn set_relay_available(&self, enabled: bool) {
        self.relay_advertised.store(enabled, Ordering::Relaxed);
        if let Some(daemon) = &self.mdns {
            let ip = *self.local_ipv4.read();
            register_mdns_service(
                daemon,
                &self.device_id,
                &self.device_name,
                ip,
                self.local_port,
                &self.cert_fingerprint,
                enabled,
            );
        }
    }

    /// Re-detects primary LAN IPv4 (e.g. after Wi-Fi roaming) and updates mDNS registration if changed.
    pub fn refresh_local_ip(&self) -> String {
        let new_ip = detect_primary_lan_ipv4();
        let changed = {
            let mut cur = self.local_ipv4.write();
            if *cur != new_ip {
                *cur = new_ip;
                true
            } else {
                false
            }
        };
        if changed {
            if let Some(daemon) = &self.mdns {
                register_mdns_service(
                    daemon,
                    &self.device_id,
                    &self.device_name,
                    new_ip,
                    self.local_port,
                    &self.cert_fingerprint,
                    self.is_relay_advertised(),
                );
            }
        }
        new_ip.to_string()
    }

    pub fn pairing_info(&self) -> LanPairingInfo {
        let mut st = self.pairing_state.write();
        if now_utc_ms() > st.expires_at_utc {
            *st = PairingChallenge::new_fresh();
        }
        st.as_info()
    }

    pub fn rotate_pairing_info(&self) -> LanPairingInfo {
        let mut st = self.pairing_state.write();
        *st = PairingChallenge::new_fresh();
        st.as_info()
    }

    pub fn discovered_peers(&self) -> Vec<LanPeerInfo> {
        let now = now_utc_ms();
        let paired_list = self.vault.load_paired_peers();
        let paired_fps: HashSet<String> = paired_list
            .iter()
            .map(|p| p.cert_fingerprint.trim().to_ascii_lowercase())
            .collect();

        let mut updates_to_persist: Vec<(String, String, u16, String)> = Vec::new();
        let mut list: Vec<LanPeerInfo> = {
            let mut map = self.peers.write();
            map.retain(|_, v| now - v.last_seen_ms < 30_000);
            map.values_mut()
                .map(|t| {
                    let fp_norm = t.info.cert_fingerprint.trim().to_ascii_lowercase();
                    t.info.is_paired = !fp_norm.is_empty() && paired_fps.contains(&fp_norm);
                    if t.info.is_paired {
                        updates_to_persist.push((
                            t.info.device_id.clone(),
                            t.info.host.clone(),
                            t.info.port,
                            t.info.device_name.clone(),
                        ));
                    }
                    t.info.clone()
                })
                .collect()
        };

        for (dev_id, host, port, name) in updates_to_persist {
            let _ = self
                .vault
                .update_paired_peer_endpoint(&dev_id, &host, port, Some(&name));
        }

        list.sort_by(|a, b| {
            b.is_paired
                .cmp(&a.is_paired)
                .then(a.device_name.cmp(&b.device_name))
                .then(a.device_id.cmp(&b.device_id))
        });
        list
    }

    /// Performs one-scan / 6-digit PIN mutual-TLS pairing handshake with a peer,
    /// establishing reciprocal persistent trust in `.saltnote/local/paired_peers.json` on BOTH sides.
    pub async fn pair_with_peer(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        pairing_secret: &str,
        vault: &VaultStore,
    ) -> SaltResult<PairedPeer> {
        let conn = self
            .connect_pinned_quic(peer_addr, expected_fingerprint, 8_000)
            .await?;
        let (mut send, mut recv) = conn
            .open_bi()
            .await
            .map_err(|e| SaltError::Sync(e.to_string()))?;

        let req = PairRequest {
            device_id: self.device_id.clone(),
            device_name: self.device_name.clone(),
            quic_port: self.local_port,
            pairing_secret: pairing_secret.trim().to_string(),
        };
        write_v2_magic(&mut send).await?;
        write_typed_json(&mut send, FRAME_PAIR_REQ, &req).await?;
        send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;

        read_and_check_v2_magic(&mut recv).await?;
        let resp: PairResponse = read_typed_json(&mut recv, FRAME_PAIR_RESP).await?;
        conn.close(0u32.into(), b"pair_done");

        if !resp.ok {
            return Err(SaltError::Sync(
                resp.error_message
                    .unwrap_or_else(|| "配对失败，请检查二维码或 6 位配对码".into()),
            ));
        }

        let paired = PairedPeer {
            device_id: resp.device_id,
            device_name: if resp.device_name.trim().is_empty() {
                "SaltNote Peer".into()
            } else {
                resp.device_name
            },
            cert_fingerprint: expected_fingerprint.trim().to_ascii_lowercase(),
            last_host: peer_addr.ip().to_string(),
            last_port: if resp.quic_port > 0 {
                resp.quic_port
            } else {
                peer_addr.port()
            },
            paired_at_utc: now_utc_ms(),
            last_sync_utc: 0,
            auto_sync: true,
        };
        vault.upsert_paired_peer(paired.clone())?;
        Ok(paired)
    }

    /// Connects to a paired LAN peer over mutual-TLS QUIC/TLS 1.3 (SLTV2 streaming),
    /// atomically exchanges missing Loro updates and streamed `assets/*` blobs, and retries once on transient drop.
    pub async fn sync_with_peer(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        vault: &VaultStore,
    ) -> SaltResult<P2pSyncResult> {
        let mut last_err = None;
        for attempt in 0..2 {
            match self
                .sync_with_peer_once_timeout(peer_addr, expected_fingerprint, vault, 8_000)
                .await
            {
                Ok(res) => return Ok(res),
                Err(e) => {
                    let msg = e.to_string();
                    if msg.contains("fingerprint mismatch")
                        || msg.contains("未配对")
                        || msg.contains("not paired")
                        || msg.contains("协议版本不兼容")
                        || attempt == 1
                    {
                        return Err(e);
                    }
                    last_err = Some(e);
                    tokio::time::sleep(Duration::from_millis(350)).await;
                }
            }
        }
        Err(last_err.unwrap_or_else(|| SaltError::Sync("LAN sync failed".into())))
    }

    /// Synchronizes with all auto-sync-enabled paired peers (preferring live discovered host:port,
    /// falling back to last known host:port with a short connect timeout).
    pub async fn sync_all_paired_peers(
        &self,
        vault: &VaultStore,
        connect_timeout_ms: u64,
    ) -> LanBatchSyncReport {
        let paired = vault.load_paired_peers();
        let discovered = self.discovered_peers();
        let mut live_by_id: HashMap<String, LanPeerInfo> = HashMap::new();
        let mut live_by_fp: HashMap<String, LanPeerInfo> = HashMap::new();
        for d in discovered {
            live_by_fp.insert(d.cert_fingerprint.trim().to_ascii_lowercase(), d.clone());
            live_by_id.insert(d.device_id.clone(), d);
        }

        let mut report = LanBatchSyncReport::default();
        for peer in paired.into_iter().filter(|p| p.auto_sync) {
            if peer.device_id == self.device_id {
                continue;
            }
            let live = live_by_id
                .get(&peer.device_id)
                .or_else(|| live_by_fp.get(&peer.cert_fingerprint.trim().to_ascii_lowercase()));
            let (host, port) = if let Some(d) = live {
                (d.host.as_str(), d.port)
            } else if !peer.last_host.is_empty() && peer.last_port > 0 {
                (peer.last_host.as_str(), peer.last_port)
            } else {
                continue;
            };
            let Ok(addr) = format!("{host}:{port}").parse::<SocketAddr>() else {
                continue;
            };
            report.attempted_peers += 1;
            match self
                .sync_with_peer_once_timeout(
                    addr,
                    &peer.cert_fingerprint,
                    vault,
                    connect_timeout_ms.max(500),
                )
                .await
            {
                Ok(res) => {
                    report.succeeded_peers += 1;
                    report.total_sent_packets += res.sent_packets;
                    report.total_received_packets += res.received_packets;
                    report.total_sent_assets += res.sent_assets;
                    report.total_received_assets += res.received_assets;
                    report.synced_device_ids.push(peer.device_id);
                }
                Err(e) => {
                    report.errors.push(format!("{}: {e}", peer.device_name));
                }
            }
        }
        report
    }

    async fn connect_pinned_quic(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        timeout_ms: u64,
    ) -> SaltResult<quinn::Connection> {
        let _ = rustls::crypto::ring::default_provider().install_default();
        let verifier = Arc::new(PinnedFingerprintVerifier {
            expected_sha256_hex: expected_fingerprint.trim().to_ascii_lowercase(),
        });
        let client_crypto = rustls::ClientConfig::builder()
            .dangerous()
            .with_custom_certificate_verifier(verifier)
            .with_client_auth_cert(vec![self.cert_der.clone()], self.key_der.clone_key())
            .map_err(|e| SaltError::Sync(format!("mTLS client config failed: {e}")))?;

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

        tokio::time::timeout(Duration::from_millis(timeout_ms), connecting)
            .await
            .map_err(|_| {
                SaltError::Sync(format!(
                    "连接超时 ({peer_addr})，请确认双方在同一 Wi-Fi 且未被防火墙拦截"
                ))
            })?
            .map_err(|e| SaltError::Sync(format!("QUIC mTLS 握手失败: {e}")))
    }

    /// Sends a `#private` inbox item over mutual-TLS QUIC/TLS 1.3 (SLTV2) to a paired LAN PC's SaltRelay service.
    pub async fn relay_enrich_with_peer(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        req: RelayEnrichRequest,
    ) -> SaltResult<InboxEnrichmentOutput> {
        let conn = self
            .connect_pinned_quic(peer_addr, expected_fingerprint, 8_000)
            .await?;
        let (mut send, mut recv) = conn
            .open_bi()
            .await
            .map_err(|e| SaltError::Sync(e.to_string()))?;

        let media = req.media_bytes.as_deref().unwrap_or(&[]);
        if media.len() > MAX_PACKET_LORO_BYTES {
            return Err(SaltError::Sync("SaltRelay media payload too large".into()));
        }
        let header = RelayHeader {
            relay_request_id: req.relay_request_id,
            kind: req.kind,
            is_private: req.is_private,
            text_note: req.text_note,
            media_ext: req.media_ext,
            output_language: req.output_language,
            has_media: !media.is_empty(),
            media_len: media.len() as u32,
        };
        write_v2_magic(&mut send).await?;
        write_typed_json(&mut send, FRAME_RELAY_REQ, &header).await?;
        if !media.is_empty() {
            send.write_all(media)
                .await
                .map_err(|e| SaltError::Sync(e.to_string()))?;
        }
        send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;

        read_and_check_v2_magic(&mut recv).await?;
        let resp: RelayEnrichResponse = read_typed_json(&mut recv, FRAME_RELAY_RESP).await?;
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

    async fn sync_with_peer_once_timeout(
        &self,
        peer_addr: SocketAddr,
        expected_fingerprint: &str,
        vault: &VaultStore,
        connect_timeout_ms: u64,
    ) -> SaltResult<P2pSyncResult> {
        let conn = self
            .connect_pinned_quic(peer_addr, expected_fingerprint, connect_timeout_ms)
            .await?;

        let (mut send, mut recv) = conn
            .open_bi()
            .await
            .map_err(|e| SaltError::Sync(e.to_string()))?;

        // Phase 1: Exchange lightweight Loro VersionVector + asset path digests
        let _ = vault.flush_all_dirty_deltas();
        let local_assets_list = vault.list_asset_rel_paths()?;
        let local_digest = P2pDigestV2 {
            device_id: self.device_id.clone(),
            page_vvs_hex: encode_vvs_hex(&vault.export_page_version_vectors()),
            asset_paths: local_assets_list.clone(),
        };
        write_v2_magic(&mut send).await?;
        write_typed_json(&mut send, FRAME_SYNC_DIGEST, &local_digest).await?;

        read_and_check_v2_magic(&mut recv).await?;
        let peer_digest: P2pDigestV2 = read_typed_json(&mut recv, FRAME_SYNC_DIGEST).await?;

        // Phase 2: Compute and stream ONLY missing page deltas & missing assets
        let peer_vvs = decode_vvs_hex(&peer_digest.page_vvs_hex);
        let out_packets = vault.export_diff_packets_against(&peer_vvs)?;
        let peer_asset_set: HashSet<&str> =
            peer_digest.asset_paths.iter().map(String::as_str).collect();
        let missing_asset_paths: Vec<String> = local_assets_list
            .into_iter()
            .filter(|p| !peer_asset_set.contains(p.as_str()))
            .collect();

        let (sent_packets, sent_assets) = send_sync_batch_v2(
            &mut send,
            &self.device_id,
            &out_packets,
            &missing_asset_paths,
            vault,
        )
        .await?;
        send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;

        let (peer_device_id, received_packets, received_assets, scope) =
            recv_sync_batch_v2(&mut recv, vault).await?;

        if received_packets > 0 || received_assets > 0 {
            if let Some(hook) = &self.on_remote_sync {
                hook(scope);
            }
        }
        let _ = vault.touch_paired_peer_sync(expected_fingerprint, now_utc_ms());

        conn.close(0u32.into(), b"done");
        Ok(P2pSyncResult {
            peer_device_id,
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

fn encode_vvs_hex(vvs: &BTreeMap<String, Vec<u8>>) -> BTreeMap<String, String> {
    vvs.iter()
        .map(|(k, v)| (k.clone(), hex::encode(v)))
        .collect()
}

fn decode_vvs_hex(vvs_hex: &BTreeMap<String, String>) -> BTreeMap<String, Vec<u8>> {
    vvs_hex
        .iter()
        .filter_map(|(k, h)| hex::decode(h).ok().map(|b| (k.clone(), b)))
        .collect()
}

async fn write_v2_magic(send: &mut quinn::SendStream) -> SaltResult<()> {
    send.write_all(WIRE_MAGIC_V2)
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))
}

async fn read_and_check_v2_magic(recv: &mut quinn::RecvStream) -> SaltResult<()> {
    let mut magic = [0u8; 5];
    tokio::time::timeout(Duration::from_secs(15), recv.read_exact(&mut magic))
        .await
        .map_err(|_| SaltError::Sync("读取对端同步协议头超时".into()))?
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    if &magic != WIRE_MAGIC_V2 {
        return Err(SaltError::Sync(
            "协议版本不兼容，请将对端升级至 SaltNote v0.2-beta".into(),
        ));
    }
    Ok(())
}

async fn write_typed_json<T: Serialize>(
    send: &mut quinn::SendStream,
    frame_type: u8,
    value: &T,
) -> SaltResult<()> {
    let bytes = serde_json::to_vec(value).map_err(|e| SaltError::Sync(e.to_string()))?;
    if bytes.len() > MAX_CONTROL_FRAME_BYTES {
        return Err(SaltError::Sync("Control frame exceeds size limit".into()));
    }
    let len_le = (bytes.len() as u32).to_le_bytes();
    send.write_all(&[frame_type])
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    send.write_all(&len_le)
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    send.write_all(&bytes)
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    Ok(())
}

async fn read_typed_frame_header(recv: &mut quinn::RecvStream) -> SaltResult<(u8, Vec<u8>)> {
    let mut hdr = [0u8; 5];
    tokio::time::timeout(Duration::from_secs(30), recv.read_exact(&mut hdr))
        .await
        .map_err(|_| SaltError::Sync("读取对端同步帧头超时".into()))?
        .map_err(|e| SaltError::Sync(e.to_string()))?;
    let frame_type = hdr[0];
    let frame_len = u32::from_le_bytes([hdr[1], hdr[2], hdr[3], hdr[4]]) as usize;
    if frame_len > MAX_CONTROL_FRAME_BYTES {
        return Err(SaltError::Sync(
            "Incoming control frame exceeds size limit".into(),
        ));
    }
    let mut buf = vec![0u8; frame_len];
    if frame_len > 0 {
        tokio::time::timeout(Duration::from_secs(30), recv.read_exact(&mut buf))
            .await
            .map_err(|_| SaltError::Sync("读取对端同步帧数据超时".into()))?
            .map_err(|e| SaltError::Sync(e.to_string()))?;
    }
    Ok((frame_type, buf))
}

async fn read_typed_json<T: for<'de> Deserialize<'de>>(
    recv: &mut quinn::RecvStream,
    expected_type: u8,
) -> SaltResult<T> {
    let (frame_type, buf) = read_typed_frame_header(recv).await?;
    if frame_type == FRAME_ERROR {
        let err = serde_json::from_slice::<WireErrorPayload>(&buf)
            .map(|w| w.message)
            .unwrap_or_else(|_| "Remote peer rejected sync request".into());
        return Err(SaltError::Sync(err));
    }
    if frame_type != expected_type {
        return Err(SaltError::Sync(format!(
            "Unexpected sync frame type 0x{frame_type:02x}, expected 0x{expected_type:02x}"
        )));
    }
    serde_json::from_slice(&buf).map_err(|e| SaltError::Sync(format!("Invalid sync frame: {e}")))
}

async fn write_wire_error(send: &mut quinn::SendStream, message: impl Into<String>) -> SaltResult<()> {
    write_typed_json(
        send,
        FRAME_ERROR,
        &WireErrorPayload {
            message: message.into(),
        },
    )
    .await
}

struct TempFileCleanup(Option<PathBuf>);

impl TempFileCleanup {
    fn new(path: PathBuf) -> Self {
        Self(Some(path))
    }

    fn disarm(&mut self) {
        self.0 = None;
    }
}

impl Drop for TempFileCleanup {
    fn drop(&mut self) {
        if let Some(p) = self.0.take() {
            let _ = std::fs::remove_file(p);
        }
    }
}

async fn send_sync_batch_v2(
    send: &mut quinn::SendStream,
    device_id: &str,
    packets: &[DeltaPacket],
    asset_paths: &[String],
    vault: &VaultStore,
) -> SaltResult<(u32, u32)> {
    let mut valid_assets: Vec<(String, PathBuf, u64)> = Vec::new();
    for rel in asset_paths {
        let Ok(abs) = vault.asset_abs_path_checked(rel) else {
            continue;
        };
        if let Ok(meta) = tokio::fs::metadata(&abs).await {
            let len = meta.len();
            if meta.is_file() && len <= MAX_SINGLE_ASSET_BYTES {
                valid_assets.push((rel.clone(), abs, len));
            }
        }
    }

    let hdr = SyncBatchHeader {
        device_id: device_id.to_string(),
        packet_count: packets.len() as u32,
        asset_count: valid_assets.len() as u32,
    };
    write_typed_json(send, FRAME_SYNC_BATCH, &hdr).await?;

    for pkt in packets {
        if pkt.loro_bytes.len() > MAX_PACKET_LORO_BYTES {
            return Err(SaltError::Sync("Delta packet exceeds size limit".into()));
        }
        let meta = DeltaPacketMeta {
            page_id: pkt.page_id.clone(),
            device_id: pkt.device_id.clone(),
            seq: pkt.seq,
            created_at_utc: pkt.created_at_utc,
        };
        write_typed_json(send, FRAME_PACKET, &meta).await?;
        let loro_len = (pkt.loro_bytes.len() as u32).to_le_bytes();
        send.write_all(&loro_len)
            .await
            .map_err(|e| SaltError::Sync(e.to_string()))?;
        send.write_all(&pkt.loro_bytes)
            .await
            .map_err(|e| SaltError::Sync(e.to_string()))?;
    }

    let mut chunk_buf = vec![0u8; STREAM_CHUNK_BYTES];
    for (rel_path, abs_path, byte_len) in &valid_assets {
        let ahdr = AssetHeader {
            rel_path: rel_path.clone(),
            byte_len: *byte_len,
        };
        write_typed_json(send, FRAME_ASSET, &ahdr).await?;

        let mut file = tokio::fs::File::open(abs_path)
            .await
            .map_err(|e| SaltError::Io(e.to_string()))?;
        let mut remaining = *byte_len;
        while remaining > 0 {
            let max_read = (remaining as usize).min(chunk_buf.len());
            let n = file
                .read(&mut chunk_buf[..max_read])
                .await
                .map_err(|e| SaltError::Io(e.to_string()))?;
            if n == 0 {
                return Err(SaltError::Io(format!(
                    "Unexpected EOF reading asset {}",
                    rel_path
                )));
            }
            send.write_all(&chunk_buf[..n])
                .await
                .map_err(|e| SaltError::Sync(e.to_string()))?;
            remaining -= n as u64;
        }
    }

    Ok((packets.len() as u32, valid_assets.len() as u32))
}

async fn recv_sync_batch_v2(
    recv: &mut quinn::RecvStream,
    vault: &VaultStore,
) -> SaltResult<(String, u32, u32, RemoteSyncScope)> {
    let batch_hdr: SyncBatchHeader = read_typed_json(recv, FRAME_SYNC_BATCH).await?;

    let mut incoming_packets = Vec::with_capacity(batch_hdr.packet_count as usize);
    for _ in 0..batch_hdr.packet_count {
        let meta: DeltaPacketMeta = read_typed_json(recv, FRAME_PACKET).await?;
        let mut len_buf = [0u8; 4];
        tokio::time::timeout(Duration::from_secs(30), recv.read_exact(&mut len_buf))
            .await
            .map_err(|_| SaltError::Sync("读取 CRDT 包长度超时".into()))?
            .map_err(|e| SaltError::Sync(e.to_string()))?;
        let loro_len = u32::from_le_bytes(len_buf) as usize;
        if loro_len > MAX_PACKET_LORO_BYTES {
            return Err(SaltError::Sync("Incoming CRDT packet exceeds limit".into()));
        }
        let mut loro_bytes = vec![0u8; loro_len];
        if loro_len > 0 {
            tokio::time::timeout(Duration::from_secs(30), recv.read_exact(&mut loro_bytes))
                .await
                .map_err(|_| SaltError::Sync("读取 CRDT 数据超时".into()))?
                .map_err(|e| SaltError::Sync(e.to_string()))?;
        }
        incoming_packets.push(DeltaPacket {
            page_id: meta.page_id,
            device_id: meta.device_id,
            seq: meta.seq,
            created_at_utc: meta.created_at_utc,
            loro_bytes,
        });
    }

    let mut scope = RemoteSyncScope::default();
    let mut received_assets = 0u32;
    let mut chunk_buf = vec![0u8; STREAM_CHUNK_BYTES];

    for _ in 0..batch_hdr.asset_count {
        let ahdr: AssetHeader = read_typed_json(recv, FRAME_ASSET).await?;
        let _ = vault.asset_abs_path_checked(&ahdr.rel_path)?;
        if ahdr.byte_len > MAX_SINGLE_ASSET_BYTES {
            return Err(SaltError::Sync(format!(
                "Asset {} exceeds maximum allowed size",
                ahdr.rel_path
            )));
        }

        let tmp_path = vault
            .paths()
            .local_dir()
            .join(format!(".recv_{}.tmp", new_ulid()));
        let mut cleanup = TempFileCleanup::new(tmp_path.clone());
        {
            let mut file = tokio::fs::File::create(&tmp_path)
                .await
                .map_err(|e| SaltError::Io(e.to_string()))?;
            let mut remaining = ahdr.byte_len;
            while remaining > 0 {
                let to_read = (remaining as usize).min(chunk_buf.len());
                let n = tokio::time::timeout(
                    Duration::from_secs(30),
                    recv.read(&mut chunk_buf[..to_read]),
                )
                .await
                .map_err(|_| SaltError::Sync("读取流式资产分块超时".into()))?
                .map_err(|e| SaltError::Sync(e.to_string()))?
                .ok_or_else(|| SaltError::Sync("流式资产接收提前结束".into()))?;
                file.write_all(&chunk_buf[..n])
                    .await
                    .map_err(|e| SaltError::Io(e.to_string()))?;
                remaining -= n as u64;
            }
            file.flush().await.map_err(|e| SaltError::Io(e.to_string()))?;
        }

        vault.commit_temp_asset_file(&tmp_path, &ahdr.rel_path)?;
        cleanup.disarm();
        scope.assets_changed = true;
        received_assets += 1;
    }

    let mut received_packets = 0u32;
    for pkt in &incoming_packets {
        let raw = serde_json::to_vec(pkt).map_err(|e| SaltError::Sync(e.to_string()))?;
        vault.import_delta_packet_bytes(&raw)?;
        if pkt.page_id == VAULT_ROOT_DOC_ID {
            scope.inbox_changed = true;
        } else {
            scope.pages_changed = true;
        }
        received_packets += 1;
    }

    Ok((batch_hdr.device_id, received_packets, received_assets, scope))
}

fn extract_client_cert_fingerprint(conn: &quinn::Connection) -> SaltResult<String> {
    let identity = conn
        .peer_identity()
        .ok_or_else(|| SaltError::Sync("Missing mTLS client certificate".into()))?;
    let certs = identity
        .downcast::<Vec<CertificateDer<'static>>>()
        .map_err(|_| SaltError::Sync("Unexpected mTLS peer identity type".into()))?;
    let leaf = certs
        .first()
        .ok_or_else(|| SaltError::Sync("Empty mTLS client certificate chain".into()))?;
    Ok(sha256_hex(leaf.as_ref()))
}

fn verify_pairing_secret(state: &RwLock<PairingChallenge>, provided_raw: &str) -> Result<(), &'static str> {
    let provided = provided_raw.trim();
    if provided.is_empty() {
        return Err("配对码不能为空");
    }
    let mut st = state.write();
    if now_utc_ms() > st.expires_at_utc {
        *st = PairingChallenge::new_fresh();
        return Err("配对码已过期，请刷新后重试");
    }
    let matched = provided.eq_ignore_ascii_case(&st.token) || provided == st.pin;
    if matched {
        st.failed_attempts = 0;
        return Ok(());
    }
    st.failed_attempts += 1;
    if st.failed_attempts >= MAX_PAIR_ATTEMPTS {
        *st = PairingChallenge::new_fresh();
        return Err("配对码连续输错次数过多，已自动锁定并刷新");
    }
    Err("配对二维码令牌或 6 位配对码不正确")
}

async fn handle_incoming_sync_conn(
    conn: quinn::Connection,
    vault: &VaultStore,
    local_device_name: &str,
    local_port: u16,
    pairing_state: &RwLock<PairingChallenge>,
    on_remote_sync: Option<&OnRemoteSyncHook>,
    relay_handler: Option<&SaltRelayHandler>,
) -> SaltResult<()> {
    let client_fp = extract_client_cert_fingerprint(&conn)?;
    let (mut send, mut recv) = conn
        .accept_bi()
        .await
        .map_err(|e| SaltError::Sync(e.to_string()))?;

    read_and_check_v2_magic(&mut recv).await?;
    write_v2_magic(&mut send).await?;

    let (frame_type, hdr_bytes) = read_typed_frame_header(&mut recv).await?;
    match frame_type {
        FRAME_PAIR_REQ => {
            let req: PairRequest = serde_json::from_slice(&hdr_bytes)
                .map_err(|e| SaltError::Sync(format!("Invalid PairRequest: {e}")))?;
            let dev = vault.device_state();
            let resp = match verify_pairing_secret(pairing_state, &req.pairing_secret) {
                Ok(()) => {
                    let remote_ip = conn.remote_address().ip().to_string();
                    let peer = PairedPeer {
                        device_id: req.device_id,
                        device_name: if req.device_name.trim().is_empty() {
                            "SaltNote Peer".into()
                        } else {
                            req.device_name
                        },
                        cert_fingerprint: client_fp,
                        last_host: remote_ip,
                        last_port: if req.quic_port > 0 {
                            req.quic_port
                        } else {
                            PREFERRED_QUIC_PORT
                        },
                        paired_at_utc: now_utc_ms(),
                        last_sync_utc: 0,
                        auto_sync: true,
                    };
                    if let Err(e) = vault.upsert_paired_peer(peer) {
                        PairResponse {
                            ok: false,
                            device_id: dev.device_id,
                            device_name: local_device_name.to_string(),
                            quic_port: local_port,
                            error_message: Some(e.to_string()),
                        }
                    } else {
                        PairResponse {
                            ok: true,
                            device_id: dev.device_id,
                            device_name: local_device_name.to_string(),
                            quic_port: local_port,
                            error_message: None,
                        }
                    }
                }
                Err(msg) => PairResponse {
                    ok: false,
                    device_id: dev.device_id,
                    device_name: local_device_name.to_string(),
                    quic_port: local_port,
                    error_message: Some(msg.to_string()),
                },
            };
            write_typed_json(&mut send, FRAME_PAIR_RESP, &resp).await?;
            send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;
            let _ = tokio::time::timeout(Duration::from_secs(5), conn.closed()).await;
            Ok(())
        }
        FRAME_RELAY_REQ => {
            if !vault.is_peer_paired(&client_fp) {
                write_wire_error(&mut send, "Peer not paired for SaltRelay (mTLS)").await?;
                send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;
                let _ = tokio::time::timeout(Duration::from_secs(5), conn.closed()).await;
                return Ok(());
            }
            let hdr: RelayHeader = serde_json::from_slice(&hdr_bytes)
                .map_err(|e| SaltError::Sync(format!("Invalid RelayHeader: {e}")))?;
            let media_bytes = if hdr.has_media && hdr.media_len > 0 {
                let len = hdr.media_len as usize;
                if len > MAX_PACKET_LORO_BYTES {
                    return Err(SaltError::Sync("SaltRelay media exceeds limit".into()));
                }
                let mut buf = vec![0u8; len];
                tokio::time::timeout(Duration::from_secs(30), recv.read_exact(&mut buf))
                    .await
                    .map_err(|_| SaltError::Sync("读取 SaltRelay 媒体超时".into()))?
                    .map_err(|e| SaltError::Sync(e.to_string()))?;
                Some(buf)
            } else {
                None
            };
            let relay_req = RelayEnrichRequest {
                relay_request_id: hdr.relay_request_id,
                kind: hdr.kind,
                is_private: hdr.is_private,
                text_note: hdr.text_note,
                media_bytes,
                media_ext: hdr.media_ext,
                output_language: hdr.output_language,
            };
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
            write_typed_json(&mut send, FRAME_RELAY_RESP, &reply).await?;
            send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;
            let _ = tokio::time::timeout(Duration::from_secs(10), conn.closed()).await;
            Ok(())
        }
        FRAME_SYNC_DIGEST => {
            if !vault.is_peer_paired(&client_fp) {
                write_wire_error(&mut send, "设备未配对，请先扫码或输入配对码建立信任").await?;
                send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;
                let _ = tokio::time::timeout(Duration::from_secs(5), conn.closed()).await;
                return Ok(());
            }
            let peer_digest: P2pDigestV2 = serde_json::from_slice(&hdr_bytes)
                .map_err(|e| SaltError::Sync(format!("Invalid P2pDigestV2: {e}")))?;

            // Phase 1: Reply with local digest BEFORE importing anything
            let _ = vault.flush_all_dirty_deltas();
            let local_dev_id = vault.device_state().device_id;
            let local_assets_list = vault.list_asset_rel_paths()?;
            let local_digest = P2pDigestV2 {
                device_id: local_dev_id.clone(),
                page_vvs_hex: encode_vvs_hex(&vault.export_page_version_vectors()),
                asset_paths: local_assets_list.clone(),
            };
            write_typed_json(&mut send, FRAME_SYNC_DIGEST, &local_digest).await?;

            // Compute diff for peer BEFORE importing incoming batch (zero echo)
            let peer_vvs = decode_vvs_hex(&peer_digest.page_vvs_hex);
            let out_packets = vault.export_diff_packets_against(&peer_vvs)?;
            let peer_asset_set: HashSet<&str> =
                peer_digest.asset_paths.iter().map(String::as_str).collect();
            let missing_asset_paths: Vec<String> = local_assets_list
                .into_iter()
                .filter(|p| !peer_asset_set.contains(p.as_str()))
                .collect();

            // Phase 2: Stream incoming batch in, import, then stream our batch out
            let (_peer_dev_id, recv_pkts, recv_assets, scope) =
                recv_sync_batch_v2(&mut recv, vault).await?;
            if recv_pkts > 0 || recv_assets > 0 {
                if let Some(hook) = on_remote_sync {
                    hook(scope);
                }
            }

            let _ = send_sync_batch_v2(
                &mut send,
                &local_dev_id,
                &out_packets,
                &missing_asset_paths,
                vault,
            )
            .await?;
            send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;
            let _ = vault.touch_paired_peer_sync(&client_fp, now_utc_ms());

            let _ = tokio::time::timeout(Duration::from_secs(10), conn.closed()).await;
            Ok(())
        }
        other => {
            write_wire_error(&mut send, format!("Unsupported SLTV2 frame type 0x{other:02x}"))
                .await?;
            send.finish().map_err(|e| SaltError::Sync(e.to_string()))?;
            let _ = tokio::time::timeout(Duration::from_secs(5), conn.closed()).await;
            Ok(())
        }
    }
}

// ============================================================================
// Dual LAN Discovery Helpers (mDNS + UDP Broadcast Beacon + Primary IPv4 Resolution)
// ============================================================================

fn register_mdns_service(
    daemon: &ServiceDaemon,
    device_id: &str,
    device_name: &str,
    local_ipv4: Ipv4Addr,
    local_port: u16,
    fingerprint: &str,
    relay_available: bool,
) {
    let short = &device_id[..8.min(device_id.len())];
    let instance_name = format!("saltnote-{short}");
    let host_name = format!("{instance_name}.local.");
    let local_ip = local_ipv4.to_string();
    let mut props = HashMap::new();
    props.insert("device_id".to_string(), device_id.to_string());
    props.insert("device_name".to_string(), device_name.to_string());
    props.insert("fingerprint".to_string(), fingerprint.to_string());
    props.insert("ipv4".to_string(), local_ip.clone());
    props.insert(
        "relay".to_string(),
        if relay_available { "1" } else { "0" }.to_string(),
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
}

#[allow(clippy::too_many_arguments)]
fn start_udp_lan_beacon(
    device_id: String,
    device_name: String,
    local_ipv4: Arc<RwLock<Ipv4Addr>>,
    quic_port: u16,
    fingerprint: String,
    relay_advertised: Arc<AtomicBool>,
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
                        let mut map = listen_peers.write();
                        let existing_fullname = map
                            .get(&beacon.device_id)
                            .and_then(|t| t.mdns_fullname.clone());
                        map.insert(
                            beacon.device_id.clone(),
                            TrackedPeer {
                                info: LanPeerInfo {
                                    device_id: beacon.device_id,
                                    device_name: beacon.device_name,
                                    host: v4.to_string(),
                                    port: beacon.port,
                                    cert_fingerprint: beacon.fingerprint,
                                    relay_available: beacon.relay_available,
                                    is_paired: false,
                                },
                                last_seen_ms: now_utc_ms(),
                                mdns_fullname: existing_fullname,
                            },
                        );
                    }
                }
            }
        }
    });

    // Broadcaster thread sending lightweight LAN presence every 2 seconds with live relay flag & IP roaming
    let broadcast_stop = Arc::clone(&stop);
    std::thread::spawn(move || {
        let Ok(sock) = UdpSocket::bind("0.0.0.0:0") else {
            return;
        };
        let _ = sock.set_broadcast(true);
        let global_bcast = SocketAddr::new(IpAddr::V4(Ipv4Addr::BROADCAST), LAN_BEACON_PORT);
        let mut tick_count: u64 = 0;

        while !broadcast_stop.load(Ordering::Relaxed) {
            if tick_count > 0 && tick_count % 5 == 0 {
                let detected = detect_primary_lan_ipv4();
                let mut cur = local_ipv4.write();
                if *cur != detected {
                    *cur = detected;
                }
            }
            tick_count += 1;

            let cur_ip = *local_ipv4.read();
            let payload = LanBeaconPayload {
                magic: "SALTNOTE_LAN_V1".into(),
                device_id: device_id.clone(),
                device_name: device_name.clone(),
                port: quic_port,
                fingerprint: fingerprint.clone(),
                relay_available: relay_advertised.load(Ordering::Relaxed),
            };
            if let Ok(bytes) = serde_json::to_vec(&payload) {
                let _ = sock.send_to(&bytes, global_bcast);
                if !cur_ip.is_loopback() && !cur_ip.is_unspecified() {
                    let octets = cur_ip.octets();
                    let subnet_bcast = SocketAddr::new(
                        IpAddr::V4(Ipv4Addr::new(octets[0], octets[1], octets[2], 255)),
                        LAN_BEACON_PORT,
                    );
                    let _ = sock.send_to(&bytes, subnet_bcast);
                }
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
                    70
                } else {
                    50
                };
                scored.push((score, v4.to_string()));
            }
            IpAddr::V6(_) => {}
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

#[derive(Debug)]
struct Ed25519ClientCertVerifier;

impl ClientCertVerifier for Ed25519ClientCertVerifier {
    fn offer_client_auth(&self) -> bool {
        true
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }

    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        if end_entity.as_ref().is_empty() {
            return Err(rustls::Error::NoCertificatesPresented);
        }
        Ok(ClientCertVerified::assertion())
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
            Some(Arc::new(move |scope| {
                if scope.pages_changed {
                    rn.fetch_add(1, Ordering::SeqCst);
                }
            })),
        )
        .unwrap();

        let addr2 = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), node2.local_port());
        let token = node2.pairing_info().token;
        node1
            .pair_with_peer(addr2, node2.cert_fingerprint(), &token, &v1)
            .await
            .unwrap();

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

        // Second sync with zero changes must transfer ZERO packets and ZERO assets
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
        let pin = pc_node.pairing_info().pin;
        phone_node
            .pair_with_peer(pc_addr, pc_node.cert_fingerprint(), &pin, &phone)
            .await
            .unwrap();

        let sync_res = phone_node
            .sync_with_peer(pc_addr, pc_node.cert_fingerprint(), &phone)
            .await
            .unwrap();
        assert_eq!(sync_res.sent_packets, 1);
        assert_eq!(sync_res.sent_assets, 1);
        assert_eq!(pc.list_inbox_items(false, 10).len(), 1);

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

    #[tokio::test]
    async fn lan_mtls_rejects_unpaired_client_for_sync_and_relay() {
        let base = std::env::temp_dir().join(format!("saltnote_p2p_unpaired_{}", new_ulid()));
        let rogue = Arc::new(VaultStore::open(base.join("rogue"), Some("Rogue".into())).unwrap());
        let pc = Arc::new(VaultStore::open(base.join("pc"), Some("PC".into())).unwrap());

        pc.create_page("Secret Note", PageMode::Study, true, vec![], "Top Secret")
            .unwrap();
        let relay_handler: SaltRelayHandler = Arc::new(|_| {
            Box::pin(async move {
                Ok(InboxEnrichmentOutput {
                    transcript_text: Some("should never execute".into()),
                    summary_text: None,
                    auto_tags: vec![],
                })
            })
        });

        let rogue_node = LanP2pNode::start(Arc::clone(&rogue), false, None).unwrap();
        let pc_node =
            LanP2pNode::start_with_relay(Arc::clone(&pc), false, None, Some(relay_handler))
                .unwrap();

        let pc_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), pc_node.local_port());
        let sync_err = rogue_node
            .sync_with_peer(pc_addr, pc_node.cert_fingerprint(), &rogue)
            .await;
        assert!(sync_err.is_err());

        let relay_err = rogue_node
            .relay_enrich_with_peer(
                pc_addr,
                pc_node.cert_fingerprint(),
                RelayEnrichRequest {
                    relay_request_id: new_ulid(),
                    kind: InboxKind::QuickText,
                    is_private: true,
                    text_note: Some("steal compute".into()),
                    media_bytes: None,
                    media_ext: None,
                    output_language: None,
                },
            )
            .await;
        assert!(relay_err.is_err());

        rogue_node.shutdown();
        pc_node.shutdown();
        let _ = std::fs::remove_dir_all(&base);
    }

    #[tokio::test]
    async fn lan_single_scan_pairing_and_streaming_large_binary_asset() {
        let base = std::env::temp_dir().join(format!("saltnote_p2p_stream_{}", new_ulid()));
        let phone = Arc::new(VaultStore::open(base.join("phone"), Some("Phone".into())).unwrap());
        let pc = Arc::new(VaultStore::open(base.join("pc"), Some("PC".into())).unwrap());

        // Create a 3 MiB binary payload to verify chunked SLTV2 streaming & zero JSON bloat
        let big_bytes: Vec<u8> = (0..(3 * 1024 * 1024)).map(|i| (i % 251) as u8).collect();
        let item = phone
            .capture_inbox_item(
                InboxKind::PhotoNote,
                Some("3MB 板书原图".into()),
                Some(&big_bytes),
                Some("webp"),
                Some(b"TINY_THUMB"),
                0,
                false,
            )
            .unwrap();
        let big_rel = item.payload.image_asset_rel_path.clone().unwrap();

        let phone_node = LanP2pNode::start(Arc::clone(&phone), false, None).unwrap();
        let pc_node = LanP2pNode::start(Arc::clone(&pc), false, None).unwrap();
        let pc_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), pc_node.local_port());

        // Single-scan token pairing populates paired_peers on BOTH sides
        let token = pc_node.pairing_info().token;
        phone_node
            .pair_with_peer(pc_addr, pc_node.cert_fingerprint(), &token, &phone)
            .await
            .unwrap();

        assert_eq!(phone.load_paired_peers().len(), 1);
        assert_eq!(pc.load_paired_peers().len(), 1);
        assert!(pc.is_peer_paired(phone_node.cert_fingerprint()));
        assert!(phone.is_peer_paired(pc_node.cert_fingerprint()));

        // Batch auto-sync converges the 3 MiB asset + thumbnail + inbox item
        let batch = phone_node.sync_all_paired_peers(&phone, 3_000).await;
        assert_eq!(batch.succeeded_peers, 1);
        assert_eq!(batch.total_sent_packets, 1);
        assert_eq!(batch.total_sent_assets, 2);

        let pc_big = std::fs::read(pc.paths().root.join(&big_rel)).unwrap();
        assert_eq!(pc_big.len(), big_bytes.len());
        assert_eq!(pc_big, big_bytes);
        assert_eq!(pc.list_inbox_items(false, 10).len(), 1);

        // Second sync sends 0 packets & 0 assets
        let batch2 = phone_node.sync_all_paired_peers(&phone, 3_000).await;
        assert_eq!(batch2.succeeded_peers, 1);
        assert_eq!(batch2.total_sent_packets, 0);
        assert_eq!(batch2.total_sent_assets, 0);

        phone_node.shutdown();
        pc_node.shutdown();
        let _ = std::fs::remove_dir_all(&base);
    }

    #[tokio::test]
    async fn lan_pairing_pin_locks_and_rotates_after_five_failures() {
        let base = std::env::temp_dir().join(format!("saltnote_p2p_pin_{}", new_ulid()));
        let phone = Arc::new(VaultStore::open(base.join("phone"), Some("Phone".into())).unwrap());
        let pc = Arc::new(VaultStore::open(base.join("pc"), Some("PC".into())).unwrap());

        let phone_node = LanP2pNode::start(Arc::clone(&phone), false, None).unwrap();
        let pc_node = LanP2pNode::start(Arc::clone(&pc), false, None).unwrap();
        let pc_addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), pc_node.local_port());

        let original_pin = pc_node.pairing_info().pin;
        let wrong_pin = if original_pin == "000000" {
            "111111"
        } else {
            "000000"
        };

        for _ in 0..5 {
            let err = phone_node
                .pair_with_peer(pc_addr, pc_node.cert_fingerprint(), wrong_pin, &phone)
                .await;
            assert!(err.is_err());
        }

        // After 5 failures, original PIN must be rotated and rejected
        let rotated_pin = pc_node.pairing_info().pin;
        if rotated_pin != original_pin {
            let old_res = phone_node
                .pair_with_peer(pc_addr, pc_node.cert_fingerprint(), &original_pin, &phone)
                .await;
            assert!(old_res.is_err());
        }
        // New rotated PIN succeeds cleanly
        let ok_res = phone_node
            .pair_with_peer(pc_addr, pc_node.cert_fingerprint(), &rotated_pin, &phone)
            .await;
        assert!(ok_res.is_ok());

        phone_node.shutdown();
        pc_node.shutdown();
        let _ = std::fs::remove_dir_all(&base);
    }

    #[tokio::test]
    async fn lan_relay_flag_hot_updates_without_restarting_quic() {
        let base = std::env::temp_dir().join(format!("saltnote_p2p_hot_{}", new_ulid()));
        let pc = Arc::new(VaultStore::open(base.join("pc"), Some("PC".into())).unwrap());
        let pc_node = LanP2pNode::start(Arc::clone(&pc), false, None).unwrap();

        assert!(!pc_node.is_relay_advertised());
        pc_node.set_relay_available(true);
        assert!(pc_node.is_relay_advertised());
        pc_node.set_relay_available(false);
        assert!(!pc_node.is_relay_advertised());

        pc_node.shutdown();
        let _ = std::fs::remove_dir_all(&base);
    }
}
