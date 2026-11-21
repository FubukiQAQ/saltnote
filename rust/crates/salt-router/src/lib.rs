use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use futures_util::StreamExt;
use image::ImageFormat;
use reqwest::Client;
use salt_types::{
    is_local_or_rfc1918_endpoint, redact_sensitive_text, AiProviderKind, HaloPreset,
    ProviderConfig, SaltError, SaltResult, VisionPolicy,
};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::io::Cursor;
use std::sync::Arc;
use std::time::Duration;
use tokio_util::sync::CancellationToken;

/// Platform-backed secret store bridged from Kotlin (Android Keystore / Windows DPAPI / Keychain / SecretService).
pub trait SecretResolver: Send + Sync {
    fn resolve_secret(&self, key_ref: &str) -> SaltResult<String>;
}

/// Callback sink receiving streamed Markdown deltas from an active Salt Halo AI request.
pub trait StreamSink: Send + Sync {
    fn on_delta(&self, text_chunk: &str);
    fn on_complete(&self, full_text: &str);
    fn on_error(&self, err: &SaltError);
}

#[derive(Debug, Clone)]
pub struct HaloInferenceRequest {
    pub page_is_private: bool,
    pub preset: HaloPreset,
    pub context_markdown: String,
    pub user_prompt_extra: Option<String>,
    pub webp_slice: Option<Vec<u8>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FlashcardDraft {
    pub front: String,
    pub back: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConnectivityTestResult {
    pub ok: bool,
    pub latency_ms: u64,
    pub message: String,
}

/// Normalizes an OpenAI-compatible base URL: removes accidental whitespace/newlines and trailing endpoint paths.
pub fn normalize_openai_base_url(raw: &str) -> String {
    let first_line = raw
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .unwrap_or("");
    let cleaned: String = first_line
        .split_whitespace()
        .take_while(|tok| !tok.starts_with("sk-"))
        .collect::<Vec<_>>()
        .join("")
        .chars()
        .filter(|c| !c.is_control() && !c.is_whitespace())
        .collect();
    let trimmed = cleaned.trim_end_matches('/');
    if let Some(stripped) = trimmed.strip_suffix("/chat/completions") {
        stripped.trim_end_matches('/').to_string()
    } else if let Some(stripped) = trimmed.strip_suffix("/models") {
        stripped.trim_end_matches('/').to_string()
    } else {
        trimmed.to_string()
    }
}

fn sanitize_provider(provider: &ProviderConfig) -> ProviderConfig {
    let clean_url = normalize_openai_base_url(&provider.base_url);
    let clean_model = provider
        .model
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .unwrap_or("")
        .chars()
        .filter(|c| !c.is_control())
        .collect::<String>()
        .trim()
        .to_string();
    ProviderConfig {
        id: provider.id.clone(),
        name: provider.name.clone(),
        kind: provider.kind,
        base_url: clean_url,
        model: clean_model,
        api_key_ref: provider.api_key_ref.trim().to_string(),
    }
}

/// Converts a WebP canvas slice to PNG for maximum OpenAI-compatible vision endpoint compatibility.
fn convert_webp_to_png(webp_bytes: &[u8]) -> (&'static str, Vec<u8>) {
    if let Ok(img) = image::load_from_memory(webp_bytes) {
        let mut png_buf = Cursor::new(Vec::new());
        if img.write_to(&mut png_buf, ImageFormat::Png).is_ok() {
            return ("image/png", png_buf.into_inner());
        }
    }
    ("image/webp", webp_bytes.to_vec())
}

/// Pure-Rust BYOK multimodal AI router with hard `#private` privacy boundary and instant stream cancellation.
pub struct SaltRouter {
    http: Client,
    secrets: Arc<dyn SecretResolver>,
}

impl SaltRouter {
    pub fn new(secrets: Arc<dyn SecretResolver>) -> SaltResult<Self> {
        let http = Client::builder()
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(120))
            .build()
            .map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?;
        Ok(Self { http, secrets })
    }

    /// Hard privacy boundary enforced before any secret lookup or outbound network call.
    pub fn enforce_privacy_guard(
        page_is_private: bool,
        provider: &ProviderConfig,
    ) -> SaltResult<()> {
        let clean = sanitize_provider(provider);
        if page_is_private && !is_local_or_rfc1918_endpoint(&clean.base_url) {
            return Err(SaltError::PrivacyBlocked {
                endpoint: clean.base_url,
                reason: "Page is marked #private; only loopback or RFC1918 local endpoints (e.g. Ollama) are permitted".into(),
            });
        }
        Ok(())
    }

    fn resolve_api_key(&self, provider: &ProviderConfig) -> SaltResult<String> {
        let key_ref = provider.api_key_ref.trim();
        if key_ref.is_empty() || provider.kind == AiProviderKind::OllamaLocal {
            return Ok(String::new());
        }
        Ok(self.secrets.resolve_secret(key_ref)?.trim().to_string())
    }

    /// Streams a Salt Halo multimodal/text request with instant cancellation via `CancellationToken`.
    pub async fn stream_halo(
        &self,
        provider: &ProviderConfig,
        req: &HaloInferenceRequest,
        cancel: CancellationToken,
        sink: Arc<dyn StreamSink>,
    ) -> SaltResult<String> {
        let provider = sanitize_provider(provider);
        // 1. Privacy hard gate BEFORE touching secrets or socket
        if let Err(e) = Self::enforce_privacy_guard(req.page_is_private, &provider) {
            sink.on_error(&e);
            return Err(e);
        }
        if cancel.is_cancelled() {
            sink.on_error(&SaltError::Cancelled);
            return Err(SaltError::Cancelled);
        }

        let api_key = match self.resolve_api_key(&provider) {
            Ok(k) => k,
            Err(e) => {
                sink.on_error(&e);
                return Err(e);
            }
        };

        let result = match provider.kind {
            AiProviderKind::OpenAiCompatible | AiProviderKind::OllamaLocal => {
                self.stream_openai(&provider, &api_key, req, cancel, sink.clone())
                    .await
            }
            AiProviderKind::Anthropic => {
                self.stream_anthropic(&provider, &api_key, req, cancel, sink.clone())
                    .await
            }
            AiProviderKind::Gemini => {
                self.stream_gemini(&provider, &api_key, req, cancel, sink.clone())
                    .await
            }
        };

        match &result {
            Ok(full) => sink.on_complete(full),
            Err(e) => sink.on_error(e),
        }
        result
    }

    async fn stream_openai(
        &self,
        provider: &ProviderConfig,
        api_key: &str,
        req: &HaloInferenceRequest,
        cancel: CancellationToken,
        sink: Arc<dyn StreamSink>,
    ) -> SaltResult<String> {
        let base = normalize_openai_base_url(&provider.base_url);
        let url = format!("{base}/chat/completions");
        let user_text = build_user_prompt(req);

        // Try multimodal payload first when a visual slice is attached
        if let Some(webp) = &req.webp_slice {
            let (mime, encoded_img) = convert_webp_to_png(webp);
            let b64 = BASE64.encode(&encoded_img);
            let user_content = json!([
                { "type": "text", "text": user_text },
                { "type": "image_url", "image_url": { "url": format!("data:{mime};base64,{b64}") } }
            ]);
            match self
                .send_openai_stream_request(&url, &provider.model, api_key, &req.preset.system_prompt, user_content, cancel.clone(), sink.clone())
                .await
            {
                Ok(out) => return Ok(out),
                Err(SaltError::Network(msg))
                    if is_vision_unsupported_error(&msg)
                        && req.preset.vision_policy != VisionPolicy::ForceWebpSlice
                        && !req.context_markdown.trim().is_empty() =>
                {
                    // Fallback to text context if user selected a text-only OpenAI model
                    let text_content = Value::String(build_user_prompt(req));
                    return self
                        .send_openai_stream_request(
                            &url,
                            &provider.model,
                            api_key,
                            &req.preset.system_prompt,
                            text_content,
                            cancel,
                            sink,
                        )
                        .await;
                }
                Err(e) => return Err(e),
            }
        }

        self.send_openai_stream_request(
            &url,
            &provider.model,
            api_key,
            &req.preset.system_prompt,
            Value::String(user_text),
            cancel,
            sink,
        )
        .await
    }

    async fn send_openai_stream_request(
        &self,
        url: &str,
        model: &str,
        api_key: &str,
        system_prompt: &str,
        user_content: Value,
        cancel: CancellationToken,
        sink: Arc<dyn StreamSink>,
    ) -> SaltResult<String> {
        let payload = json!({
            "model": model,
            "stream": true,
            "messages": [
                { "role": "system", "content": system_prompt },
                { "role": "user", "content": user_content }
            ]
        });

        let mut builder = self.http.post(url).json(&payload);
        if !api_key.is_empty() {
            builder = builder.bearer_auth(api_key);
        }

        let resp = tokio::select! {
            _ = cancel.cancelled() => return Err(SaltError::Cancelled),
            res = builder.send() => res.map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?,
        };

        if !resp.status().is_success() {
            let code = resp.status();
            let text = resp.text().await.unwrap_or_default();
            let detail = extract_api_error_message(&text).unwrap_or(text);
            return Err(SaltError::Network(redact_sensitive_text(&format!(
                "OpenAI HTTP {code}: {detail}"
            ))));
        }

        // If a proxy ignored `stream: true` and returned plain JSON, decode it directly
        let content_type = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_ascii_lowercase();
        if content_type.contains("application/json") {
            let body = resp
                .text()
                .await
                .map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?;
            if let Some(err_msg) = extract_api_error_message(&body) {
                return Err(SaltError::Network(redact_sensitive_text(&err_msg)));
            }
            if let Some(text) = extract_openai_non_stream_text(&body) {
                if !text.is_empty() {
                    sink.on_delta(&text);
                }
                return Ok(text);
            }
            return Err(SaltError::Network(
                "OpenAI endpoint returned JSON without choices content".into(),
            ));
        }

        self.consume_openai_sse_stream(resp, cancel, sink).await
    }

    async fn stream_anthropic(
        &self,
        provider: &ProviderConfig,
        api_key: &str,
        req: &HaloInferenceRequest,
        cancel: CancellationToken,
        sink: Arc<dyn StreamSink>,
    ) -> SaltResult<String> {
        let base = provider.base_url.trim_end_matches('/');
        let url = if base.ends_with("/v1") {
            format!("{base}/messages")
        } else {
            format!("{base}/v1/messages")
        };

        let user_text = build_user_prompt(req);
        let mut content_blocks = vec![json!({ "type": "text", "text": user_text })];
        if let Some(webp) = &req.webp_slice {
            let (mime, img_bytes) = convert_webp_to_png(webp);
            content_blocks.push(json!({
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": mime,
                    "data": BASE64.encode(&img_bytes)
                }
            }));
        }

        let payload = json!({
            "model": provider.model,
            "max_tokens": 2048,
            "stream": true,
            "system": req.preset.system_prompt,
            "messages": [
                { "role": "user", "content": content_blocks }
            ]
        });

        let builder = self
            .http
            .post(&url)
            .header("x-api-key", api_key)
            .header("anthropic-version", "2023-06-01")
            .json(&payload);

        let resp = tokio::select! {
            _ = cancel.cancelled() => return Err(SaltError::Cancelled),
            res = builder.send() => res.map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?,
        };

        if !resp.status().is_success() {
            let code = resp.status();
            let text = resp.text().await.unwrap_or_default();
            return Err(SaltError::Network(redact_sensitive_text(&format!(
                "Anthropic HTTP {code}: {text}"
            ))));
        }

        self.consume_sse_stream(resp, cancel, sink, |data| {
            let Ok(v) = serde_json::from_str::<Value>(data) else {
                return Ok(None);
            };
            if let Some(err) = extract_json_error_value(&v) {
                return Err(SaltError::Network(redact_sensitive_text(&err)));
            }
            if v.get("type").and_then(Value::as_str) == Some("content_block_delta") {
                Ok(v.get("delta")
                    .and_then(|d| d.get("text"))
                    .and_then(Value::as_str)
                    .map(|s| s.to_string()))
            } else {
                Ok(None)
            }
        })
        .await
    }

    async fn stream_gemini(
        &self,
        provider: &ProviderConfig,
        api_key: &str,
        req: &HaloInferenceRequest,
        cancel: CancellationToken,
        sink: Arc<dyn StreamSink>,
    ) -> SaltResult<String> {
        let base = provider.base_url.trim_end_matches('/');
        let url = format!(
            "{base}/v1beta/models/{}:streamGenerateContent?alt=sse",
            provider.model
        );

        let user_text = build_user_prompt(req);
        let mut parts = vec![json!({ "text": user_text })];
        if let Some(webp) = &req.webp_slice {
            parts.push(json!({
                "inline_data": {
                    "mime_type": "image/webp",
                    "data": BASE64.encode(webp)
                }
            }));
        }

        let payload = json!({
            "system_instruction": { "parts": [{ "text": req.preset.system_prompt }] },
            "contents": [{ "role": "user", "parts": parts }]
        });

        let builder = self
            .http
            .post(&url)
            .header("x-goog-api-key", api_key)
            .json(&payload);

        let resp = tokio::select! {
            _ = cancel.cancelled() => return Err(SaltError::Cancelled),
            res = builder.send() => res.map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?,
        };

        if !resp.status().is_success() {
            let code = resp.status();
            let text = resp.text().await.unwrap_or_default();
            return Err(SaltError::Network(redact_sensitive_text(&format!(
                "Gemini HTTP {code}: {text}"
            ))));
        }

        self.consume_sse_stream(resp, cancel, sink, |data| {
            let Ok(v) = serde_json::from_str::<Value>(data) else {
                return Ok(None);
            };
            if let Some(err) = extract_json_error_value(&v) {
                return Err(SaltError::Network(redact_sensitive_text(&err)));
            }
            let Some(parts) = v
                .get("candidates")
                .and_then(|c| c.get(0))
                .and_then(|c| c.get("content"))
                .and_then(|c| c.get("parts"))
                .and_then(Value::as_array)
            else {
                return Ok(None);
            };
            let mut acc = String::new();
            for p in parts {
                if let Some(t) = p.get("text").and_then(Value::as_str) {
                    acc.push_str(t);
                }
            }
            Ok(if acc.is_empty() { None } else { Some(acc) })
        })
        .await
    }

    /// OpenAI SSE consumer using a UTF-8 safe byte buffer, supporting `delta.content`,
    /// non-stream `message.content`, mid-stream JSON errors, and `reasoning_content` fallback.
    async fn consume_openai_sse_stream(
        &self,
        resp: reqwest::Response,
        cancel: CancellationToken,
        sink: Arc<dyn StreamSink>,
    ) -> SaltResult<String> {
        let mut stream = resp.bytes_stream();
        let mut byte_buf: Vec<u8> = Vec::new();
        let mut full_output = String::new();
        let mut reasoning_fallback = String::new();

        loop {
            let next_chunk = tokio::select! {
                _ = cancel.cancelled() => {
                    return Err(SaltError::Cancelled);
                }
                item = stream.next() => item,
            };

            let Some(chunk_res) = next_chunk else {
                break;
            };
            let chunk = chunk_res
                .map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?;
            byte_buf.extend_from_slice(&chunk);

            while let Some(nl_idx) = byte_buf.iter().position(|&b| b == b'\n') {
                let line_bytes: Vec<u8> = byte_buf.drain(..=nl_idx).collect();
                let raw_line = String::from_utf8_lossy(&line_bytes).trim().to_string();
                process_openai_sse_line(
                    &raw_line,
                    &mut full_output,
                    &mut reasoning_fallback,
                    sink.as_ref(),
                )?;
            }
        }

        // Flush any trailing line without terminating newline (or non-SSE JSON response)
        if !byte_buf.is_empty() {
            let raw_tail = String::from_utf8_lossy(&byte_buf).trim().to_string();
            if !raw_tail.is_empty() {
                if raw_tail.starts_with("data:") {
                    process_openai_sse_line(
                        &raw_tail,
                        &mut full_output,
                        &mut reasoning_fallback,
                        sink.as_ref(),
                    )?;
                } else if let Some(err) = extract_api_error_message(&raw_tail) {
                    return Err(SaltError::Network(redact_sensitive_text(&err)));
                } else if let Some(text) = extract_openai_non_stream_text(&raw_tail) {
                    if !text.is_empty() {
                        full_output.push_str(&text);
                        sink.on_delta(&text);
                    }
                }
            }
        }

        if full_output.is_empty() && !reasoning_fallback.is_empty() {
            sink.on_delta(&reasoning_fallback);
            return Ok(reasoning_fallback);
        }

        Ok(full_output)
    }

    async fn consume_sse_stream<F>(
        &self,
        resp: reqwest::Response,
        cancel: CancellationToken,
        sink: Arc<dyn StreamSink>,
        extract_delta: F,
    ) -> SaltResult<String>
    where
        F: Fn(&str) -> SaltResult<Option<String>>,
    {
        let mut stream = resp.bytes_stream();
        let mut byte_buf: Vec<u8> = Vec::new();
        let mut full_output = String::new();

        loop {
            let next_chunk = tokio::select! {
                _ = cancel.cancelled() => {
                    return Err(SaltError::Cancelled);
                }
                item = stream.next() => item,
            };

            let Some(chunk_res) = next_chunk else {
                break;
            };
            let chunk = chunk_res
                .map_err(|e| SaltError::Network(redact_sensitive_text(&e.to_string())))?;
            byte_buf.extend_from_slice(&chunk);

            while let Some(nl_idx) = byte_buf.iter().position(|&b| b == b'\n') {
                let line_bytes: Vec<u8> = byte_buf.drain(..=nl_idx).collect();
                let raw_line = String::from_utf8_lossy(&line_bytes).trim().to_string();
                if let Some(data) = raw_line.strip_prefix("data:") {
                    let data = data.trim();
                    if data.is_empty() || data == "[DONE]" {
                        continue;
                    }
                    if let Some(delta) = extract_delta(data)? {
                        if !delta.is_empty() {
                            full_output.push_str(&delta);
                            sink.on_delta(&delta);
                        }
                    }
                }
            }
        }

        if !byte_buf.is_empty() {
            let raw_tail = String::from_utf8_lossy(&byte_buf).trim().to_string();
            if let Some(data) = raw_tail.strip_prefix("data:") {
                let data = data.trim();
                if !data.is_empty() && data != "[DONE]" {
                    if let Some(delta) = extract_delta(data)? {
                        if !delta.is_empty() {
                            full_output.push_str(&delta);
                            sink.on_delta(&delta);
                        }
                    }
                }
            }
        }

        Ok(full_output)
    }

    /// Tests provider API key, URL, and model reachability with real payload validation.
    pub async fn test_provider_connection(
        &self,
        provider: &ProviderConfig,
    ) -> ConnectivityTestResult {
        let start = std::time::Instant::now();
        let provider = sanitize_provider(provider);

        if reqwest::Url::parse(&provider.base_url).is_err() {
            return ConnectivityTestResult {
                ok: false,
                latency_ms: start.elapsed().as_millis() as u64,
                message: "API 地址格式无效（不能包含空格或换行）".into(),
            };
        }

        let api_key = match self.resolve_api_key(&provider) {
            Ok(k) => k,
            Err(e) => {
                return ConnectivityTestResult {
                    ok: false,
                    latency_ms: start.elapsed().as_millis() as u64,
                    message: redact_sensitive_text(&e.to_string()),
                };
            }
        };

        if matches!(provider.kind, AiProviderKind::OpenAiCompatible | AiProviderKind::OllamaLocal) {
            return self.test_openai_connection(&provider, &api_key, start).await;
        }

        let base = provider.base_url.trim_end_matches('/');
        let req = match provider.kind {
            AiProviderKind::Anthropic => {
                let url = if base.ends_with("/v1") {
                    format!("{base}/models")
                } else {
                    format!("{base}/v1/models")
                };
                self.http
                    .get(url)
                    .header("x-api-key", &api_key)
                    .header("anthropic-version", "2023-06-01")
            }
            AiProviderKind::Gemini => self
                .http
                .get(format!("{base}/v1beta/models"))
                .header("x-goog-api-key", &api_key),
            _ => unreachable!(),
        };

        match req.timeout(Duration::from_secs(12)).send().await {
            Ok(resp) if resp.status().is_success() => ConnectivityTestResult {
                ok: true,
                latency_ms: start.elapsed().as_millis() as u64,
                message: format!("连接成功 ({})", resp.status()),
            },
            Ok(resp) => {
                let code = resp.status();
                let body = resp.text().await.unwrap_or_default();
                let detail = extract_api_error_message(&body)
                    .unwrap_or_else(|| format!("HTTP {code}"));
                ConnectivityTestResult {
                    ok: false,
                    latency_ms: start.elapsed().as_millis() as u64,
                    message: redact_sensitive_text(&detail),
                }
            }
            Err(e) => ConnectivityTestResult {
                ok: false,
                latency_ms: start.elapsed().as_millis() as u64,
                message: redact_sensitive_text(&e.to_string()),
            },
        }
    }

    async fn test_openai_connection(
        &self,
        provider: &ProviderConfig,
        api_key: &str,
        start: std::time::Instant,
    ) -> ConnectivityTestResult {
        let base = normalize_openai_base_url(&provider.base_url);
        let chat_url = format!("{base}/chat/completions");
        let payload = json!({
            "model": provider.model,
            "stream": false,
            "max_tokens": 1,
            "messages": [{ "role": "user", "content": "ping" }]
        });

        let mut builder = self.http.post(&chat_url).json(&payload);
        if !api_key.is_empty() {
            builder = builder.bearer_auth(api_key);
        }

        match builder.timeout(Duration::from_secs(15)).send().await {
            Ok(resp) => {
                let status = resp.status();
                let body = resp.text().await.unwrap_or_default();
                if status.is_success() {
                    if let Ok(v) = serde_json::from_str::<Value>(&body) {
                        if let Some(err) = extract_json_error_value(&v) {
                            return ConnectivityTestResult {
                                ok: false,
                                latency_ms: start.elapsed().as_millis() as u64,
                                message: redact_sensitive_text(&err),
                            };
                        }
                        if v.get("choices").is_some() || v.get("id").is_some() {
                            return ConnectivityTestResult {
                                ok: true,
                                latency_ms: start.elapsed().as_millis() as u64,
                                message: format!("连接成功 ({} · {})", status.as_u16(), provider.model),
                            };
                        }
                    }
                    // Reject HTML SPA portals returning HTTP 200
                    return ConnectivityTestResult {
                        ok: false,
                        latency_ms: start.elapsed().as_millis() as u64,
                        message: "接口返回非 JSON 响应，请检查 API 地址是否缺少 /v1".into(),
                    };
                }

                let detail = extract_api_error_message(&body).unwrap_or_else(|| {
                    if !base.ends_with("/v1") && (status.as_u16() == 404 || status.as_u16() == 405) {
                        format!("HTTP {}（尝试在 API 地址末尾添加 /v1）", status.as_u16())
                    } else {
                        format!("HTTP {status}")
                    }
                });
                ConnectivityTestResult {
                    ok: false,
                    latency_ms: start.elapsed().as_millis() as u64,
                    message: redact_sensitive_text(&detail),
                }
            }
            Err(e) => ConnectivityTestResult {
                ok: false,
                latency_ms: start.elapsed().as_millis() as u64,
                message: redact_sensitive_text(&e.to_string()),
            },
        }
    }
}

fn process_openai_sse_line(
    raw_line: &str,
    full_output: &mut String,
    reasoning_fallback: &mut String,
    sink: &dyn StreamSink,
) -> SaltResult<()> {
    let Some(data) = raw_line.strip_prefix("data:") else {
        return Ok(());
    };
    let data = data.trim();
    if data.is_empty() || data == "[DONE]" {
        return Ok(());
    }
    let Ok(v) = serde_json::from_str::<Value>(data) else {
        return Ok(());
    };
    if let Some(err) = extract_json_error_value(&v) {
        return Err(SaltError::Network(redact_sensitive_text(&err)));
    }
    let Some(choice) = v.get("choices").and_then(|c| c.get(0)) else {
        return Ok(());
    };

    if let Some(delta) = choice.get("delta") {
        if let Some(content) = delta.get("content").and_then(Value::as_str) {
            if !content.is_empty() {
                full_output.push_str(content);
                sink.on_delta(content);
            }
        } else if let Some(text) = delta.get("text").and_then(Value::as_str) {
            if !text.is_empty() {
                full_output.push_str(text);
                sink.on_delta(text);
            }
        }
        if let Some(reason) = delta.get("reasoning_content").and_then(Value::as_str) {
            reasoning_fallback.push_str(reason);
        }
    } else if let Some(msg_content) = choice
        .get("message")
        .and_then(|m| m.get("content"))
        .and_then(Value::as_str)
    {
        if !msg_content.is_empty() {
            full_output.push_str(msg_content);
            sink.on_delta(msg_content);
        }
    }
    Ok(())
}

fn extract_openai_non_stream_text(body: &str) -> Option<String> {
    let v: Value = serde_json::from_str(body).ok()?;
    let msg = v.get("choices")?.get(0)?.get("message")?;
    let content = msg.get("content").and_then(Value::as_str).unwrap_or("");
    if !content.is_empty() {
        return Some(content.to_string());
    }
    msg.get("reasoning_content")
        .and_then(Value::as_str)
        .filter(|s| !s.is_empty())
        .map(|s| s.to_string())
}

fn extract_json_error_value(v: &Value) -> Option<String> {
    let err = v.get("error")?;
    if let Some(msg) = err.get("message").and_then(Value::as_str) {
        return Some(msg.to_string());
    }
    if let Some(s) = err.as_str() {
        return Some(s.to_string());
    }
    Some(err.to_string())
}

fn extract_api_error_message(raw_body: &str) -> Option<String> {
    let v: Value = serde_json::from_str(raw_body.trim()).ok()?;
    extract_json_error_value(&v)
}

fn is_vision_unsupported_error(msg: &str) -> bool {
    let lower = msg.to_ascii_lowercase();
    lower.contains("unexpected item type in content")
        || lower.contains("image_url")
        || lower.contains("does not support image")
        || lower.contains("not a multimodal")
        || lower.contains("vision")
}

fn build_user_prompt(req: &HaloInferenceRequest) -> String {
    let mut prompt = String::new();
    if !req.context_markdown.trim().is_empty() {
        prompt.push_str("### Note Context\n");
        prompt.push_str(&req.context_markdown);
        prompt.push_str("\n\n");
    }
    if let Some(extra) = &req.user_prompt_extra {
        if !extra.trim().is_empty() {
            prompt.push_str("### User Instruction\n");
            prompt.push_str(extra);
            prompt.push('\n');
        }
    }
    if prompt.is_empty() {
        prompt.push_str("Please analyze the attached handwritten region.");
    }
    prompt
}

/// Parses 1..=3 Q/A flashcard drafts returned by `builtin.glacier_card`.
pub fn parse_flashcard_drafts(raw_output: &str) -> Vec<FlashcardDraft> {
    let trimmed = raw_output.trim();
    let json_candidate = if let Some(start) = trimmed.find('[') {
        if let Some(end) = trimmed.rfind(']') {
            &trimmed[start..=end]
        } else {
            trimmed
        }
    } else {
        trimmed
    };

    if let Ok(mut parsed) = serde_json::from_str::<Vec<FlashcardDraft>>(json_candidate) {
        parsed.retain(|c| !c.front.trim().is_empty() && !c.back.trim().is_empty());
        parsed.truncate(3);
        if !parsed.is_empty() {
            return parsed;
        }
    }

    let mut drafts = Vec::new();
    let mut cur_q = String::new();
    let mut cur_a = String::new();
    for line in trimmed.lines() {
        let l = line.trim();
        if let Some(q) = l
            .strip_prefix("Q:")
            .or_else(|| l.strip_prefix("问："))
            .or_else(|| l.strip_prefix("**Q**:"))
        {
            if !cur_q.is_empty() && !cur_a.is_empty() {
                drafts.push(FlashcardDraft {
                    front: cur_q.trim().to_string(),
                    back: cur_a.trim().to_string(),
                });
                cur_a.clear();
            }
            cur_q = q.trim().to_string();
        } else if let Some(a) = l
            .strip_prefix("A:")
            .or_else(|| l.strip_prefix("答："))
            .or_else(|| l.strip_prefix("**A**:"))
        {
            cur_a = a.trim().to_string();
        }
    }
    if !cur_q.is_empty() && !cur_a.is_empty() {
        drafts.push(FlashcardDraft {
            front: cur_q.trim().to_string(),
            back: cur_a.trim().to_string(),
        });
    }
    drafts.truncate(3);
    drafts
}

#[cfg(test)]
mod tests {
    use super::*;
    use salt_types::default_halo_presets;
    use std::sync::Mutex;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    struct DummySecrets;
    impl SecretResolver for DummySecrets {
        fn resolve_secret(&self, key_ref: &str) -> SaltResult<String> {
            Ok(format!("dummy-secret-for-{key_ref}"))
        }
    }

    struct VecSink(Mutex<Vec<String>>);
    impl StreamSink for VecSink {
        fn on_delta(&self, text_chunk: &str) {
            self.0.lock().unwrap().push(text_chunk.to_string());
        }
        fn on_complete(&self, _full_text: &str) {}
        fn on_error(&self, _err: &SaltError) {}
    }

    #[tokio::test]
    async fn private_page_hard_blocks_cloud_provider() {
        let router = SaltRouter::new(Arc::new(DummySecrets)).unwrap();
        let cloud_provider = ProviderConfig {
            id: "openai".into(),
            name: "OpenAI".into(),
            kind: AiProviderKind::OpenAiCompatible,
            base_url: "https://api.openai.com/v1".into(),
            model: "gpt-4o-mini".into(),
            api_key_ref: "vault://openai_api_key".into(),
        };
        let req = HaloInferenceRequest {
            page_is_private: true,
            preset: default_halo_presets()[0].clone(),
            context_markdown: "Top secret personal diary".into(),
            user_prompt_extra: None,
            webp_slice: None,
        };
        let sink = Arc::new(VecSink(Mutex::new(vec![])));
        let res = router
            .stream_halo(&cloud_provider, &req, CancellationToken::new(), sink)
            .await;
        assert!(matches!(res, Err(SaltError::PrivacyBlocked { .. })));
    }

    #[test]
    fn private_page_allows_local_ollama_endpoint() {
        let local = ProviderConfig {
            id: "ollama".into(),
            name: "Local Ollama".into(),
            kind: AiProviderKind::OllamaLocal,
            base_url: "http://127.0.0.1:11434/v1".into(),
            model: "qwen2.5vl:7b".into(),
            api_key_ref: String::new(),
        };
        assert!(SaltRouter::enforce_privacy_guard(true, &local).is_ok());

        let lan = ProviderConfig {
            base_url: "http://192.168.1.42:11434/v1".into(),
            ..local
        };
        assert!(SaltRouter::enforce_privacy_guard(true, &lan).is_ok());
    }

    #[tokio::test]
    async fn pre_cancelled_stream_aborts_immediately() {
        let router = SaltRouter::new(Arc::new(DummySecrets)).unwrap();
        let local = ProviderConfig {
            id: "ollama".into(),
            name: "Local Ollama".into(),
            kind: AiProviderKind::OllamaLocal,
            base_url: "http://127.0.0.1:11434/v1".into(),
            model: "qwen2.5vl:7b".into(),
            api_key_ref: String::new(),
        };
        let req = HaloInferenceRequest {
            page_is_private: false,
            preset: default_halo_presets()[0].clone(),
            context_markdown: "Test".into(),
            user_prompt_extra: None,
            webp_slice: None,
        };
        let cancel = CancellationToken::new();
        cancel.cancel();
        let sink = Arc::new(VecSink(Mutex::new(vec![])));
        let res = router.stream_halo(&local, &req, cancel, sink).await;
        assert!(matches!(res, Err(SaltError::Cancelled)));
    }

    #[test]
    fn parse_flashcard_json_with_fences() {
        let raw = "```json\n[{\"front\":\"FSRS 是什么?\",\"back\":\"自由间隔重复调度算法\"}]\n```";
        let cards = parse_flashcard_drafts(raw);
        assert_eq!(cards.len(), 1);
        assert_eq!(cards[0].front, "FSRS 是什么?");
    }

    #[test]
    fn openai_url_normalization_strips_whitespace_and_suffix() {
        assert_eq!(
            normalize_openai_base_url("  https://api.example.com/v1/chat/completions \n"),
            "https://api.example.com/v1"
        );
        assert_eq!(
            normalize_openai_base_url("https://api.example.com/v1/"),
            "https://api.example.com/v1"
        );
        assert_eq!(
            normalize_openai_base_url("https://api.example.com/v1/\nsk-dummy-key"),
            "https://api.example.com/v1"
        );
    }

    #[tokio::test]
    async fn openai_sse_handles_split_utf8_and_reasoning_content() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut tmp = [0u8; 2048];
            let _ = sock.read(&mut tmp).await;

            // Split a Chinese UTF-8 character ("盐") across two TCP packets!
            let line1 = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}\n\n";
            let line2 = "data: {\"choices\":[{\"delta\":{\"content\":\"你好盐晶\"}}]}\n\n";
            let line3 = "data: [DONE]\n\n";

            let bytes2 = line2.as_bytes();
            let split_idx = bytes2.windows(3).position(|w| w == "盐".as_bytes()).unwrap() + 1;

            sock.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n").await.unwrap();
            sock.write_all(line1.as_bytes()).await.unwrap();
            sock.write_all(&bytes2[..split_idx]).await.unwrap();
            sock.flush().await.unwrap();
            tokio::time::sleep(Duration::from_millis(15)).await;
            sock.write_all(&bytes2[split_idx..]).await.unwrap();
            sock.write_all(line3.as_bytes()).await.unwrap();
        });

        let router = SaltRouter::new(Arc::new(DummySecrets)).unwrap();
        let provider = ProviderConfig {
            id: "openai-byok".into(),
            name: "Mock".into(),
            kind: AiProviderKind::OpenAiCompatible,
            base_url: format!("http://{addr}/v1"),
            model: "deepseek-flash".into(),
            api_key_ref: "vault://k".into(),
        };
        let req = HaloInferenceRequest {
            page_is_private: false,
            preset: default_halo_presets()[0].clone(),
            context_markdown: "test".into(),
            user_prompt_extra: None,
            webp_slice: None,
        };
        let sink = Arc::new(VecSink(Mutex::new(vec![])));
        let out = router
            .stream_halo(&provider, &req, CancellationToken::new(), sink)
            .await
            .unwrap();
        assert_eq!(out, "你好盐晶");
    }

    #[tokio::test]
    async fn openai_test_connection_rejects_html_200_portal() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        tokio::spawn(async move {
            let (mut sock, _) = listener.accept().await.unwrap();
            let mut tmp = [0u8; 2048];
            let _ = sock.read(&mut tmp).await;
            sock.write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<!DOCTYPE html><html></html>").await.unwrap();
        });

        let router = SaltRouter::new(Arc::new(DummySecrets)).unwrap();
        let provider = ProviderConfig {
            id: "openai-byok".into(),
            name: "Mock".into(),
            kind: AiProviderKind::OpenAiCompatible,
            base_url: format!("http://{addr}"),
            model: "deepseek-flash".into(),
            api_key_ref: "vault://k".into(),
        };
        let res = router.test_provider_connection(&provider).await;
        assert!(!res.ok);
        assert!(res.message.contains("/v1"));
    }
}
