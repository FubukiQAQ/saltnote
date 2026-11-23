use fsrs::{MemoryState, DEFAULT_PARAMETERS, FSRS};
use parking_lot::Mutex;
use rusqlite::{params, Connection};
use salt_canvas::extract_markdown_context;
use salt_crdt::{DocChangeEvent, VaultStore};
use salt_types::{
    new_ulid, now_utc_ms, FlashCard, FsrsCardState, MicroSessionPlan, PageMeta, PageSnapshot,
    ReviewCardView, ReviewLog, ReviewRating, SaltError, SaltResult, SearchHit,
};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;

/// Deduplication window for concurrent cross-device reviews of the same card (15 minutes).
pub const CROSS_DEVICE_DEDUP_WINDOW_MS: i64 = 15 * 60 * 1000;
const MS_PER_DAY: f64 = 86_400_000.0;
const DESIRED_RETENTION: f32 = 0.9;

/// Local derived SQLite FTS5 + FSRS state database (`.saltnote/local/index.sqlite`).
/// Completely rebuildable from CRDT truth; hardcoded ignored by sync.
pub struct IntelEngine {
    conn: Mutex<Connection>,
}

impl IntelEngine {
    pub fn open(sqlite_path: impl AsRef<Path>) -> SaltResult<Self> {
        if let Some(parent) = sqlite_path.as_ref().parent() {
            std::fs::create_dir_all(parent).map_err(|e| SaltError::Io(e.to_string()))?;
        }
        let conn = Connection::open(sqlite_path)
            .map_err(|e| SaltError::Database(format!("Failed to open index.sqlite: {e}")))?;

        conn.execute_batch(
            r#"
            PRAGMA journal_mode = WAL;
            PRAGMA synchronous = NORMAL;

            CREATE TABLE IF NOT EXISTS pages_meta (
                page_id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                is_private INTEGER NOT NULL DEFAULT 0,
                updated_at_utc INTEGER NOT NULL
            );

            CREATE VIRTUAL TABLE IF NOT EXISTS pages_fts USING fts5(
                page_id UNINDEXED,
                title,
                body_raw UNINDEXED,
                body_tokens,
                tokenize = 'unicode61'
            );

            CREATE TABLE IF NOT EXISTS fsrs_card_state (
                card_id TEXT PRIMARY KEY,
                page_id TEXT NOT NULL,
                anchor_halo_id TEXT,
                front TEXT NOT NULL,
                back TEXT NOT NULL,
                stability REAL NOT NULL,
                difficulty REAL NOT NULL,
                reps INTEGER NOT NULL,
                lapses INTEGER NOT NULL,
                state INTEGER NOT NULL,
                last_review_utc INTEGER,
                due_at_utc INTEGER NOT NULL,
                archived INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS idx_fsrs_due ON fsrs_card_state(due_at_utc);
            "#,
        )
        .map_err(|e| SaltError::Database(format!("Schema init failed: {e}")))?;

        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    /// Rebuilds the entire local FTS5 index and FSRS derived state from `VaultStore`.
    pub fn rebuild_from_vault(&self, vault: &VaultStore) -> SaltResult<()> {
        {
            let conn = self.conn.lock();
            conn.execute_batch(
                "DELETE FROM pages_meta; DELETE FROM pages_fts; DELETE FROM fsrs_card_state;",
            )
            .map_err(|e| SaltError::Database(e.to_string()))?;
        }
        for summary in vault.list_pages() {
            if let Ok(snap) = vault.get_page_snapshot(&summary.meta.page_id) {
                self.index_page(&snap.meta, &snap.body_markdown)?;
            }
        }
        let (cards, logs) = vault.get_all_cards_and_logs();
        self.replay_and_store_fsrs(&cards, &logs)?;
        Ok(())
    }

    /// Removes a deleted page from FTS5 search index and removes its flashcards from FSRS state.
    pub fn remove_page(&self, page_id: &str) -> SaltResult<()> {
        let conn = self.conn.lock();
        conn.execute("DELETE FROM pages_meta WHERE page_id = ?1", params![page_id])
            .map_err(|e| SaltError::Database(e.to_string()))?;
        conn.execute("DELETE FROM pages_fts WHERE page_id = ?1", params![page_id])
            .map_err(|e| SaltError::Database(e.to_string()))?;
        conn.execute(
            "DELETE FROM fsrs_card_state WHERE page_id = ?1",
            params![page_id],
        )
        .map_err(|e| SaltError::Database(e.to_string()))?;
        Ok(())
    }

    /// Subscribes to `salt-crdt` `DocChangeEvent` broadcast in a background Tokio task.
    pub fn spawn_background_listener(self: &Arc<Self>, vault: Arc<VaultStore>) {
        let engine = Arc::clone(self);
        let mut rx = vault.subscribe();
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(DocChangeEvent::PageUpdated {
                        meta,
                        body_markdown,
                        ..
                    }) => {
                        let _ = engine.index_page(&meta, &body_markdown);
                    }
                    Ok(DocChangeEvent::PageDeleted { page_id }) => {
                        let _ = engine.remove_page(&page_id);
                    }
                    Ok(DocChangeEvent::CardsUpdated {
                        cards,
                        review_logs,
                        ..
                    }) => {
                        let _ = engine.replay_and_store_fsrs(&cards, &review_logs);
                    }
                    Ok(DocChangeEvent::InboxUpdated) => {}
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => {
                        let _ = engine.rebuild_from_vault(&vault);
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        });
    }

    // ------------------------------------------------------------------------
    // FTS5 Full-Text Indexing & Millisecond Highlight Search
    // ------------------------------------------------------------------------

    pub fn index_page(&self, meta: &PageMeta, body_markdown: &str) -> SaltResult<()> {
        let tags_joined = meta.tags.join(" ");
        let tokenized_title = tokenize_cjk_and_latin(&format!("{} {}", meta.title, tags_joined));
        let tokenized_body = tokenize_cjk_and_latin(body_markdown);

        let conn = self.conn.lock();
        conn.execute(
            "INSERT INTO pages_meta (page_id, title, is_private, updated_at_utc)
             VALUES (?1, ?2, ?3, ?4)
             ON CONFLICT(page_id) DO UPDATE SET
               title = excluded.title,
               is_private = excluded.is_private,
               updated_at_utc = excluded.updated_at_utc",
            params![
                meta.page_id,
                meta.title,
                if meta.is_private { 1 } else { 0 },
                meta.updated_at_utc
            ],
        )
        .map_err(|e| SaltError::Database(e.to_string()))?;

        conn.execute("DELETE FROM pages_fts WHERE page_id = ?1", params![meta.page_id])
            .map_err(|e| SaltError::Database(e.to_string()))?;
        conn.execute(
            "INSERT INTO pages_fts (page_id, title, body_raw, body_tokens) VALUES (?1, ?2, ?3, ?4)",
            params![meta.page_id, tokenized_title, body_markdown, tokenized_body],
        )
        .map_err(|e| SaltError::Database(e.to_string()))?;
        Ok(())
    }

    pub fn search(&self, query: &str, limit: usize) -> SaltResult<Vec<SearchHit>> {
        let trimmed = query.trim();
        if trimmed.is_empty() {
            return Ok(Vec::new());
        }

        let conn = self.conn.lock();
        let mut hits = Vec::new();
        let mut seen = std::collections::HashSet::new();

        // 1. Primary SQLite FTS5 ranked search (supports CJK bigrams & Latin prefix matching)
        let fts_query = build_fts5_match_query(trimmed);
        if !fts_query.is_empty() {
            if let Ok(mut stmt) = conn.prepare(
                "SELECT pages_fts.page_id, pages_meta.title, pages_meta.is_private, pages_fts.body_raw, bm25(pages_fts) AS score
                 FROM pages_fts
                 JOIN pages_meta ON pages_meta.page_id = pages_fts.page_id
                 WHERE pages_fts MATCH ?1
                 ORDER BY score ASC
                 LIMIT ?2",
            ) {
                if let Ok(rows) = stmt.query_map(params![fts_query, limit as i64], |row| {
                    let page_id: String = row.get(0)?;
                    let title: String = row.get(1)?;
                    let is_private: i64 = row.get(2)?;
                    let body_raw: String = row.get(3)?;
                    let score: f64 = row.get(4)?;
                    Ok(SearchHit {
                        page_id,
                        title,
                        snippet: build_highlighted_snippet(&body_raw, trimmed),
                        score: -score,
                        is_private: is_private != 0,
                    })
                }) {
                    for r in rows.flatten() {
                        if seen.insert(r.page_id.clone()) {
                            hits.push(r);
                        }
                    }
                }
            }
        }

        // 2. Substring fallback so infix Latin ("Note" -> "SaltNote") and symbol queries ("#private") always match
        if hits.len() < limit {
            let remaining = (limit - hits.len()) as i64;
            let lower_q = trimmed.to_lowercase();
            let mut stmt = conn
                .prepare(
                    "SELECT pages_meta.page_id, pages_meta.title, pages_meta.is_private, pages_fts.body_raw
                     FROM pages_meta
                     JOIN pages_fts ON pages_fts.page_id = pages_meta.page_id
                     WHERE instr(lower(pages_meta.title), ?1) > 0
                        OR instr(lower(pages_fts.title), ?1) > 0
                        OR instr(lower(pages_fts.body_raw), ?1) > 0
                     ORDER BY pages_meta.updated_at_utc DESC
                     LIMIT ?2",
                )
                .map_err(|e| SaltError::Database(e.to_string()))?;

            let rows = stmt
                .query_map(params![lower_q, remaining + seen.len() as i64], |row| {
                    let page_id: String = row.get(0)?;
                    let title: String = row.get(1)?;
                    let is_private: i64 = row.get(2)?;
                    let body_raw: String = row.get(3)?;
                    Ok(SearchHit {
                        page_id,
                        title,
                        snippet: build_highlighted_snippet(&body_raw, trimmed),
                        score: 0.5,
                        is_private: is_private != 0,
                    })
                })
                .map_err(|e| SaltError::Database(e.to_string()))?;

            for r in rows.flatten() {
                if hits.len() >= limit {
                    break;
                }
                if seen.insert(r.page_id.clone()) {
                    hits.push(r);
                }
            }
        }

        Ok(hits)
    }

    // ------------------------------------------------------------------------
    // Event-Sourced FSRS Replay Engine
    // ------------------------------------------------------------------------

    /// Replays append-only `ReviewLog` events with 15-minute cross-device dedup
    /// using `fsrs-rs` default parameters and persists derived state into `fsrs_card_state`.
    pub fn replay_and_store_fsrs(
        &self,
        cards: &[FlashCard],
        all_logs: &[ReviewLog],
    ) -> SaltResult<Vec<FsrsCardState>> {
        let mut logs_by_card: HashMap<&str, Vec<&ReviewLog>> = HashMap::new();
        for log in all_logs {
            logs_by_card
                .entry(log.card_id.as_str())
                .or_default()
                .push(log);
        }

        let mut states = Vec::with_capacity(cards.len());
        for card in cards {
            let card_logs = logs_by_card
                .get(card.card_id.as_str())
                .map(|v| v.as_slice())
                .unwrap_or(&[]);
            let state = compute_fsrs_state_for_card(card, card_logs)?;
            states.push(state);
        }

        let conn = self.conn.lock();
        let tx = conn
            .unchecked_transaction()
            .map_err(|e| SaltError::Database(e.to_string()))?;
        for (card, st) in cards.iter().zip(states.iter()) {
            tx.execute(
                "INSERT INTO fsrs_card_state (
                    card_id, page_id, anchor_halo_id, front, back,
                    stability, difficulty, reps, lapses, state,
                    last_review_utc, due_at_utc, archived
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)
                 ON CONFLICT(card_id) DO UPDATE SET
                    front = excluded.front,
                    back = excluded.back,
                    anchor_halo_id = excluded.anchor_halo_id,
                    stability = excluded.stability,
                    difficulty = excluded.difficulty,
                    reps = excluded.reps,
                    lapses = excluded.lapses,
                    state = excluded.state,
                    last_review_utc = excluded.last_review_utc,
                    due_at_utc = excluded.due_at_utc,
                    archived = excluded.archived",
                params![
                    st.card_id,
                    st.page_id,
                    st.anchor_halo_id,
                    st.front,
                    st.back,
                    st.stability,
                    st.difficulty,
                    st.reps,
                    st.lapses,
                    st.state as i64,
                    st.last_review_utc,
                    st.due_at_utc,
                    if card.archived { 1 } else { 0 }
                ],
            )
            .map_err(|e| SaltError::Database(e.to_string()))?;
        }
        tx.commit().map_err(|e| SaltError::Database(e.to_string()))?;
        Ok(states)
    }

    /// Returns cards due at or before `as_of_utc_ms`, sorted by `due_at_utc ASC`.
    pub fn list_due_cards(&self, as_of_utc_ms: Option<i64>) -> SaltResult<Vec<FsrsCardState>> {
        let cutoff = as_of_utc_ms.unwrap_or_else(now_utc_ms);
        let conn = self.conn.lock();
        let mut stmt = conn
            .prepare(
                "SELECT card_id, page_id, anchor_halo_id, front, back,
                        stability, difficulty, reps, lapses, state, last_review_utc, due_at_utc
                 FROM fsrs_card_state
                 WHERE archived = 0 AND due_at_utc <= ?1
                 ORDER BY due_at_utc ASC",
            )
            .map_err(|e| SaltError::Database(e.to_string()))?;
        Self::collect_cards(stmt.query_map(params![cutoff], Self::map_card_row))
    }

    pub fn list_all_cards(&self) -> SaltResult<Vec<FsrsCardState>> {
        let conn = self.conn.lock();
        let mut stmt = conn
            .prepare(
                "SELECT card_id, page_id, anchor_halo_id, front, back,
                        stability, difficulty, reps, lapses, state, last_review_utc, due_at_utc
                 FROM fsrs_card_state
                 WHERE archived = 0
                 ORDER BY due_at_utc ASC",
            )
            .map_err(|e| SaltError::Database(e.to_string()))?;
        Self::collect_cards(stmt.query_map([], Self::map_card_row))
    }

    /// Builds an anti-frustration time-boxed FSRS micro-review session (v0.2-alpha Section 3.1).
    pub fn build_micro_review_plan(
        &self,
        vault: &VaultStore,
        budget_seconds: u32,
        energy_level: u8,
        rescue_mode: bool,
        as_of_utc_ms: Option<i64>,
    ) -> SaltResult<MicroSessionPlan> {
        let now = as_of_utc_ms.unwrap_or_else(now_utc_ms);
        let budget = if budget_seconds == 0 {
            300
        } else {
            budget_seconds.clamp(60, 1800)
        };
        let (_, all_logs) = vault.get_all_cards_and_logs();
        let avg_card_secs = estimate_avg_card_duration_secs(&all_logs);
        let target_count = (budget / avg_card_secs.max(1)).clamp(5, 30) as usize;

        let due = self.list_due_cards(Some(now))?;
        let total_due_count = due.len() as u32;
        if due.is_empty() {
            return Ok(MicroSessionPlan {
                session_id: format!("msess_{}", new_ulid()),
                budget_seconds: budget,
                estimated_seconds: 0,
                cards: Vec::new(),
                total_due_count: 0,
            });
        }

        let mut page_cache: HashMap<String, Option<PageSnapshot>> = HashMap::new();
        let mut views: Vec<ReviewCardView> = Vec::with_capacity(due.len());
        for card in due {
            let snap_opt = page_cache
                .entry(card.page_id.clone())
                .or_insert_with(|| vault.get_page_snapshot(&card.page_id).ok())
                .as_ref();
            let source_page_title = snap_opt
                .map(|s| s.meta.title.clone())
                .unwrap_or_else(|| "SaltNote".into());

            let halo_opt = snap_opt.and_then(|snap| {
                card.anchor_halo_id
                    .as_deref()
                    .and_then(|hid| snap.halos.iter().find(|h| h.halo_id == hid))
            });

            let slice_asset_path = halo_opt.and_then(|h| {
                h.slice_asset_rel_path
                    .clone()
                    .filter(|p| !p.is_empty())
                    .or_else(|| {
                        h.slice_cache_path
                            .clone()
                            .filter(|p| !p.is_empty() && Path::new(p).exists())
                    })
            });

            let context_excerpt = snap_opt.and_then(|snap| {
                if let Some(halo) = halo_opt {
                    if !halo.context_excerpt.trim().is_empty() {
                        return Some(halo.context_excerpt.clone());
                    }
                    let ctx = extract_markdown_context(
                        &snap.body_markdown,
                        halo.anchor_text_offset.map(|o| o as usize),
                    );
                    if ctx.trim().is_empty() {
                        None
                    } else {
                        Some(ctx)
                    }
                } else {
                    let trimmed = snap.body_markdown.trim();
                    if trimmed.is_empty() {
                        None
                    } else {
                        Some(trimmed.chars().take(240).collect())
                    }
                }
            });

            let retrievability = compute_retrievability(card.stability, card.last_review_utc, now);
            views.push(ReviewCardView {
                card_id: card.card_id,
                page_id: card.page_id,
                anchor_halo_id: card.anchor_halo_id,
                front: card.front,
                back: card.back,
                stability: card.stability,
                difficulty: card.difficulty,
                reps: card.reps,
                lapses: card.lapses,
                state: card.state,
                last_review_utc: card.last_review_utc,
                due_at_utc: card.due_at_utc,
                retrievability,
                source_page_title,
                slice_asset_path,
                context_excerpt,
            });
        }

        let mut critical_rescue: Vec<ReviewCardView> = Vec::new();
        let mut standard_due: Vec<ReviewCardView> = Vec::new();
        let mut deep_forgotten: Vec<ReviewCardView> = Vec::new();

        for v in views {
            if v.reps > 0 && v.retrievability >= 0.70 && v.retrievability <= 0.90 {
                critical_rescue.push(v);
            } else if v.reps > 0 && v.retrievability < 0.50 {
                deep_forgotten.push(v);
            } else {
                standard_due.push(v);
            }
        }

        critical_rescue.sort_by(|a, b| {
            a.retrievability
                .partial_cmp(&b.retrievability)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then(a.due_at_utc.cmp(&b.due_at_utc))
        });
        standard_due.sort_by(|a, b| a.due_at_utc.cmp(&b.due_at_utc));
        deep_forgotten.sort_by(|a, b| {
            b.retrievability
                .partial_cmp(&a.retrievability)
                .unwrap_or(std::cmp::Ordering::Equal)
                .then(a.due_at_utc.cmp(&b.due_at_utc))
        });

        let prioritize_rescue = rescue_mode || energy_level <= 2;
        let max_deep = (target_count as f64 * 0.20).floor() as usize;
        let mut selected: Vec<ReviewCardView> = Vec::with_capacity(target_count);

        if prioritize_rescue {
            for item in critical_rescue.into_iter() {
                if selected.len() >= target_count {
                    break;
                }
                selected.push(item);
            }
            for item in standard_due.into_iter() {
                if selected.len() >= target_count {
                    break;
                }
                selected.push(item);
            }
        } else {
            let mut merged = critical_rescue;
            merged.extend(standard_due);
            merged.sort_by(|a, b| a.due_at_utc.cmp(&b.due_at_utc));
            for item in merged.into_iter() {
                if selected.len() >= target_count {
                    break;
                }
                selected.push(item);
            }
        }

        let mut deep_used = 0usize;
        let mut remaining_deep = Vec::new();
        for item in deep_forgotten {
            if selected.len() < target_count && deep_used < max_deep {
                selected.push(item);
                deep_used += 1;
            } else {
                remaining_deep.push(item);
            }
        }

        // If non-deep cards were fewer than minimum viable session (5), top up from remaining_deep
        let min_viable = 5.min(total_due_count as usize);
        for item in remaining_deep {
            if selected.len() >= min_viable {
                break;
            }
            selected.push(item);
        }

        let estimated_seconds = (selected.len() as u32) * avg_card_secs;
        Ok(MicroSessionPlan {
            session_id: format!("msess_{}", new_ulid()),
            budget_seconds: budget,
            estimated_seconds,
            cards: selected,
            total_due_count,
        })
    }

    fn map_card_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<FsrsCardState> {
        Ok(FsrsCardState {
            card_id: row.get(0)?,
            page_id: row.get(1)?,
            anchor_halo_id: row.get(2)?,
            front: row.get(3)?,
            back: row.get(4)?,
            stability: row.get(5)?,
            difficulty: row.get(6)?,
            reps: row.get(7)?,
            lapses: row.get(8)?,
            state: row.get::<_, i64>(9)? as u8,
            last_review_utc: row.get(10)?,
            due_at_utc: row.get(11)?,
        })
    }

    fn collect_cards(
        res: rusqlite::Result<
            rusqlite::MappedRows<'_, fn(&rusqlite::Row<'_>) -> rusqlite::Result<FsrsCardState>>,
        >,
    ) -> SaltResult<Vec<FsrsCardState>> {
        let rows = res.map_err(|e| SaltError::Database(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            if let Ok(item) = r {
                out.push(item);
            }
        }
        Ok(out)
    }
}

/// Pure deterministic FSRS event-sourcing reducer with 15-minute cross-device dedup.
pub fn compute_fsrs_state_for_card(
    card: &FlashCard,
    raw_logs: &[&ReviewLog],
) -> SaltResult<FsrsCardState> {
    let fsrs = FSRS::new(&DEFAULT_PARAMETERS)
        .map_err(|e| SaltError::Database(format!("FSRS init failed: {e}")))?;

    // 1. Sort chronologically with deterministic tie-breaking by `log_id`
    let mut sorted: Vec<&ReviewLog> = raw_logs.to_vec();
    sorted.sort_by(|a, b| {
        a.reviewed_at_utc
            .cmp(&b.reviewed_at_utc)
            .then(a.log_id.cmp(&b.log_id))
    });

    // 2. Deduplicate exact log_ids AND merge cross-device reviews within 15 minutes
    let mut effective_logs: Vec<&ReviewLog> = Vec::with_capacity(sorted.len());
    for log in sorted {
        if let Some(prev) = effective_logs.last() {
            if prev.log_id == log.log_id {
                continue;
            }
            if (log.reviewed_at_utc - prev.reviewed_at_utc).abs() <= CROSS_DEVICE_DEDUP_WINDOW_MS {
                // Concurrent cross-device check-in within 15 minutes -> collapse into single effective review
                continue;
            }
        }
        effective_logs.push(log);
    }

    let mut memory: Option<MemoryState> = None;
    let mut last_review_utc: Option<i64> = None;
    let mut due_at_utc: i64 = card.created_at_utc;
    let mut reps: u32 = 0;
    let mut lapses: u32 = 0;
    let mut card_state: u8 = 0; // 0=New, 1=Learning, 2=Review, 3=Relearning

    for log in effective_logs {
        let days_elapsed = match last_review_utc {
            Some(prev_ts) => ((log.reviewed_at_utc - prev_ts).max(0) as f64 / MS_PER_DAY)
                .round()
                .max(0.0) as u32,
            None => 0,
        };

        let next = fsrs
            .next_states(memory, DESIRED_RETENTION, days_elapsed)
            .map_err(|e| SaltError::Database(format!("FSRS step failed: {e}")))?;

        let chosen = match log.rating {
            ReviewRating::Again => {
                if reps > 0 {
                    lapses += 1;
                    card_state = 3;
                } else {
                    card_state = 1;
                }
                next.again
            }
            ReviewRating::Hard => {
                card_state = 2;
                next.hard
            }
            ReviewRating::Good => {
                card_state = 2;
                next.good
            }
            ReviewRating::Easy => {
                card_state = 2;
                next.easy
            }
        };

        reps += 1;
        memory = Some(chosen.memory);
        last_review_utc = Some(log.reviewed_at_utc);
        // For Again, schedule 10 minutes later; otherwise use FSRS interval in days
        let interval_ms = if log.rating == ReviewRating::Again {
            10 * 60 * 1000
        } else {
            ((chosen.interval as f64).max(1.0) * MS_PER_DAY).round() as i64
        };
        due_at_utc = log.reviewed_at_utc + interval_ms;
    }

    let (stability, difficulty) = match memory {
        Some(m) => (m.stability as f64, m.difficulty as f64),
        None => (0.0, 0.0),
    };

    Ok(FsrsCardState {
        card_id: card.card_id.clone(),
        page_id: card.page_id.clone(),
        anchor_halo_id: card.anchor_halo_id.clone(),
        front: card.front.clone(),
        back: card.back.clone(),
        stability,
        difficulty,
        reps,
        lapses,
        state: card_state,
        last_review_utc,
        due_at_utc,
    })
}

/// Computes FSRS memory retrievability $R(t, S) = (1 + (19/81) * (t/S))^{-0.5}$.
pub fn compute_retrievability(
    stability: f64,
    last_review_utc: Option<i64>,
    now_utc_ms: i64,
) -> f64 {
    let Some(last) = last_review_utc else {
        return 0.0;
    };
    if stability <= 0.0 {
        return 0.0;
    }
    let elapsed_days = ((now_utc_ms - last).max(0) as f64) / MS_PER_DAY;
    (1.0 + (19.0 / 81.0) * (elapsed_days / stability)).powf(-0.5)
}

/// Estimates recent per-card review time in seconds (cold-start default = 18s).
pub fn estimate_avg_card_duration_secs(logs: &[ReviewLog]) -> u32 {
    let mut recent: Vec<&ReviewLog> = logs.iter().filter(|l| l.duration_ms > 0).collect();
    if recent.is_empty() {
        return 18;
    }
    recent.sort_by(|a, b| b.reviewed_at_utc.cmp(&a.reviewed_at_utc));
    recent.truncate(50);
    let total_ms: u64 = recent
        .iter()
        .map(|l| (l.duration_ms as u64).clamp(2_000, 120_000))
        .sum();
    let avg_secs = ((total_ms as f64 / recent.len() as f64) / 1000.0).round() as u32;
    avg_secs.clamp(5, 90)
}

// ============================================================================
// Lightweight CJK + Latin FTS5 Tokenizer & Highlight Builder
// ============================================================================

fn is_cjk(c: char) -> bool {
    matches!(
        c as u32,
        0x4E00..=0x9FFF | 0x3400..=0x4DBF | 0x3040..=0x30FF | 0xAC00..=0xD7AF
    )
}

/// Produces whitespace-delimited tokens combining Latin/numeric words and CJK unigrams + bigrams
/// so SQLite FTS5 `unicode61` delivers fast, accurate CJK & English search without bloating APK size.
pub fn tokenize_cjk_and_latin(text: &str) -> String {
    let mut tokens: Vec<String> = Vec::new();
    let mut latin_buf = String::new();
    let mut cjk_run: Vec<char> = Vec::new();

    let flush_latin = |buf: &mut String, out: &mut Vec<String>| {
        if !buf.is_empty() {
            out.push(std::mem::take(buf).to_ascii_lowercase());
        }
    };
    let flush_cjk = |run: &mut Vec<char>, out: &mut Vec<String>| {
        if run.is_empty() {
            return;
        }
        for &c in run.iter() {
            out.push(c.to_string());
        }
        for w in run.windows(2) {
            let mut s = String::with_capacity(8);
            s.push(w[0]);
            s.push(w[1]);
            out.push(s);
        }
        run.clear();
    };

    for ch in text.chars() {
        if is_cjk(ch) {
            flush_latin(&mut latin_buf, &mut tokens);
            cjk_run.push(ch);
        } else if ch.is_alphanumeric() || ch == '_' {
            flush_cjk(&mut cjk_run, &mut tokens);
            latin_buf.push(ch);
        } else {
            flush_latin(&mut latin_buf, &mut tokens);
            flush_cjk(&mut cjk_run, &mut tokens);
        }
    }
    flush_latin(&mut latin_buf, &mut tokens);
    flush_cjk(&mut cjk_run, &mut tokens);
    tokens.join(" ")
}

fn build_fts5_match_query(raw_query: &str) -> String {
    let tokenized = tokenize_cjk_and_latin(raw_query);
    let terms: Vec<String> = tokenized
        .split_whitespace()
        .filter(|t| !t.is_empty())
        .map(|t| {
            let clean = t.replace('"', "");
            if clean.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
                format!("\"{clean}\"*")
            } else {
                format!("\"{clean}\"")
            }
        })
        .collect();
    if terms.is_empty() {
        return String::new();
    }
    terms.join(" AND ")
}

fn build_highlighted_snippet(body: &str, query: &str) -> String {
    let q = query.trim();
    if q.is_empty() {
        return body.chars().take(120).collect();
    }
    let lower_body = body.to_lowercase();
    let lower_q = q.to_lowercase();
    if let Some(byte_idx) = lower_body.find(&lower_q) {
        let prefix_chars = body[..byte_idx].chars().count();
        let chars: Vec<char> = body.chars().collect();
        let start = prefix_chars.saturating_sub(36);
        let end = (prefix_chars + q.chars().count() + 64).min(chars.len());
        let raw_slice: String = chars[start..end].iter().collect();
        let cleaned = raw_slice.replace('\n', " ");
        return format!("…{cleaned}…");
    }
    body.replace('\n', " ").chars().take(120).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use salt_types::{new_ulid, PageMode};

    /// MVP Section 6.2 Test 3: FSRS duplicate review replay across 2 devices within 5 minutes.
    #[test]
    fn fsrs_cross_device_duplicate_review_within_5_minutes_counts_once() {
        let card = FlashCard {
            card_id: "card-1".into(),
            page_id: "page-1".into(),
            anchor_halo_id: None,
            front: "什么是 FSRS？".into(),
            back: "基于 DSR 记忆模型的自由间隔重复调度算法".into(),
            created_at_utc: 1_700_000_000_000,
            archived: false,
        };

        let t0 = 1_700_000_000_000;
        let log_tablet = ReviewLog {
            log_id: "log-a".into(),
            card_id: "card-1".into(),
            rating: ReviewRating::Good,
            reviewed_at_utc: t0 + 10_000,
            duration_ms: 4200,
            device_id: "tablet-dev".into(),
        };
        let log_desktop = ReviewLog {
            log_id: "log-b".into(),
            card_id: "card-1".into(),
            rating: ReviewRating::Easy,
            reviewed_at_utc: t0 + 10_000 + 5 * 60 * 1000, // 5 minutes later offline!
            duration_ms: 3100,
            device_id: "desktop-dev".into(),
        };

        let state_single = compute_fsrs_state_for_card(&card, &[&log_tablet]).unwrap();
        let state_merged_ab =
            compute_fsrs_state_for_card(&card, &[&log_tablet, &log_desktop]).unwrap();
        let state_merged_ba =
            compute_fsrs_state_for_card(&card, &[&log_desktop, &log_tablet]).unwrap();

        // Only 1 effective review applied, zero stability inflation!
        assert_eq!(state_merged_ab.reps, 1);
        assert_eq!(state_merged_ab.due_at_utc, state_single.due_at_utc);
        assert!((state_merged_ab.stability - state_single.stability).abs() < 1e-6);
        // Order-independent across devices
        assert_eq!(state_merged_ab.due_at_utc, state_merged_ba.due_at_utc);
        assert!((state_merged_ab.stability - state_merged_ba.stability).abs() < 1e-6);
    }

    #[test]
    fn fts5_cjk_and_english_search_works() {
        let tmp = std::env::temp_dir().join(format!("saltnote_fts_{}.sqlite", new_ulid()));
        let engine = IntelEngine::open(&tmp).unwrap();
        let meta = PageMeta {
            page_id: "p1".into(),
            title: "量子力学随堂笔记".into(),
            mode: PageMode::Study,
            is_private: false,
            tags: vec!["物理".into(), "SaltNote".into()],
            created_at_utc: 1,
            updated_at_utc: 1,
            is_deleted: false,
        };
        engine
            .index_page(&meta, "薛定谔方程描述了波函数随时间的演化，属于 Quantum Mechanics 核心。支持 #private 隐私模式。")
            .unwrap();

        let hits_zh = engine.search("薛定谔方程", 10).unwrap();
        assert_eq!(hits_zh.len(), 1);
        assert_eq!(hits_zh[0].page_id, "p1");

        // Single CJK char & partial Chinese phrase
        assert_eq!(engine.search("波", 10).unwrap().len(), 1);
        assert_eq!(engine.search("量子", 10).unwrap().len(), 1);

        // Full English, prefix English, infix English, symbol & tag search
        assert_eq!(engine.search("Quantum", 10).unwrap().len(), 1);
        assert_eq!(engine.search("Quant", 10).unwrap().len(), 1);
        assert_eq!(engine.search("Note", 10).unwrap().len(), 1);
        assert_eq!(engine.search("#private", 10).unwrap().len(), 1);
        assert_eq!(engine.search("物理", 10).unwrap().len(), 1);

        // Removing the page clears FTS5 search results immediately
        engine.remove_page("p1").unwrap();
        assert!(engine.search("量子", 10).unwrap().is_empty());

        let _ = std::fs::remove_file(&tmp);
    }

    #[test]
    fn micro_session_time_budget_and_critical_rescue_sorting() {
        let base = std::env::temp_dir().join(format!("saltnote_micro_{}", new_ulid()));
        let vault = VaultStore::open(&base, Some("Phone".into())).unwrap();
        let engine = IntelEngine::open(vault.paths().sqlite_path()).unwrap();
        let page = vault
            .create_page("高数", PageMode::Study, false, vec![], "# 微积分\n\n格林公式例题")
            .unwrap();

        let now = 1_760_000_000_000_i64;
        // 1. Cold start default = 18s => 300s / 18s = 16 cards; 180s / 18s = 10 cards
        for i in 0..12 {
            let card = FlashCard {
                card_id: format!("c_rescue_{i}"),
                page_id: page.page_id.clone(),
                anchor_halo_id: None,
                front: format!("Q rescue {i}"),
                back: format!("A rescue {i}"),
                created_at_utc: now - 20 * 86_400_000,
                archived: false,
            };
            vault.upsert_card(&card).unwrap();
            vault
                .append_review_log(
                    &page.page_id,
                    &ReviewLog {
                        log_id: format!("l_r_{i}"),
                        card_id: card.card_id,
                        rating: ReviewRating::Good,
                        reviewed_at_utc: now - 5 * 86_400_000, // ~5 days ago => R in [0.70, 0.90]
                        duration_ms: 0, // zero duration keeps cold-start 18s for first check
                        device_id: "phone".into(),
                    },
                )
                .unwrap();
        }
        // Deep forgotten cards (R < 0.50)
        for i in 0..8 {
            let card = FlashCard {
                card_id: format!("c_deep_{i}"),
                page_id: page.page_id.clone(),
                anchor_halo_id: None,
                front: format!("Q deep {i}"),
                back: format!("A deep {i}"),
                created_at_utc: now - 120 * 86_400_000,
                archived: false,
            };
            vault.upsert_card(&card).unwrap();
            vault
                .append_review_log(
                    &page.page_id,
                    &ReviewLog {
                        log_id: format!("l_d_{i}"),
                        card_id: card.card_id,
                        rating: ReviewRating::Again,
                        reviewed_at_utc: now - 60 * 86_400_000, // R << 0.50
                        duration_ms: 0,
                        device_id: "phone".into(),
                    },
                )
                .unwrap();
        }

        engine.rebuild_from_vault(&vault).unwrap();
        let plan = engine
            .build_micro_review_plan(&vault, 300, 1, true, Some(now))
            .unwrap();
        // 300 / 18 = 16 target; 12 rescue + max 20% of 16 (=3) deep = 15 cards
        assert_eq!(plan.total_due_count, 20);
        assert_eq!(plan.cards.len(), 15);
        assert!(plan.cards[0].retrievability >= 0.70 && plan.cards[0].retrievability <= 0.90);
        let deep_in_plan = plan
            .cards
            .iter()
            .filter(|c| c.card_id.starts_with("c_deep_"))
            .count();
        assert!(deep_in_plan <= 3, "Deep forgotten cards capped at <=20%");

        let _ = std::fs::remove_dir_all(&base);
    }
}
