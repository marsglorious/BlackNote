use crate::meta::NoteMeta;
use parking_lot::Mutex;
use rusqlite::{params, Connection};

#[derive(Debug, thiserror::Error)]
pub enum IndexError {
    #[error("sqlite error")]
    Sqlite,
    #[error("io error")]
    Io,
}

impl From<rusqlite::Error> for IndexError {
    fn from(_: rusqlite::Error) -> Self { IndexError::Sqlite }
}

pub struct SearchIndex {
    conn: Mutex<Connection>,
}

const REQUIRED_COLS: &[&str] = &["parent", "tags", "created"];

impl SearchIndex {
    pub fn new(db_path: String) -> Result<Self, IndexError> {
        let conn = Connection::open(&db_path)?;
        let present: i64 = conn
            .query_row(
                "SELECT count(*) FROM pragma_table_info('notes') WHERE name IN ('parent','tags','created')",
                [],
                |row| row.get(0),
            )
            .unwrap_or(0);
        if (present as usize) < REQUIRED_COLS.len() {
            let _ = conn.execute_batch("DROP TABLE IF EXISTS notes;");
        }
        conn.execute_batch(
            "CREATE VIRTUAL TABLE IF NOT EXISTS notes USING fts5(
                path UNINDEXED,
                parent UNINDEXED,
                title,
                body,
                label,
                tags,
                modified UNINDEXED,
                created UNINDEXED,
                tokenize = 'unicode61 remove_diacritics 2'
            );",
        )?;
        Ok(Self { conn: Mutex::new(conn) })
    }

    #[allow(clippy::too_many_arguments)]
    pub fn upsert(
        &self,
        path: String,
        parent: String,
        title: String,
        body: String,
        label: Option<String>,
        tags: Vec<String>,
        modified_millis: i64,
        created_millis: i64,
    ) -> Result<(), IndexError> {
        let conn = self.conn.lock();
        conn.execute("DELETE FROM notes WHERE path = ?1", params![&path])?;
        let tags_joined = tags.join(" ");
        conn.execute(
            "INSERT INTO notes(path, parent, title, body, label, tags, modified, created)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            params![
                &path, &parent, &title, &body, label.as_deref(), &tags_joined,
                modified_millis, created_millis
            ],
        )?;
        Ok(())
    }

    pub fn delete(&self, path: String) -> Result<(), IndexError> {
        let conn = self.conn.lock();
        conn.execute("DELETE FROM notes WHERE path = ?1", params![&path])?;
        Ok(())
    }

    pub fn query(&self, q: String, limit: u32) -> Result<Vec<NoteMeta>, IndexError> {
        let q = q.trim().to_string();
        if q.is_empty() { return self.all_sorted(limit); }
        let escaped = escape_fts(&q);
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT path, parent, title, snippet(notes, 3, '', '', '…', 16) AS preview, modified, created, tags, label
             FROM notes WHERE notes MATCH ?1 ORDER BY rank, modified DESC LIMIT ?2",
        )?;
        let rows = stmt
            .query_map(params![escaped, limit as i64], |row| Ok(row_to_meta(row)))?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn all_sorted(&self, limit: u32) -> Result<Vec<NoteMeta>, IndexError> {
        let conn = self.conn.lock();
        let mut stmt = conn.prepare(
            "SELECT path, parent, title, substr(body, 1, 180) AS preview, modified, created, tags, label
             FROM notes ORDER BY COALESCE(NULLIF(created,0), modified) DESC LIMIT ?1",
        )?;
        let rows = stmt
            .query_map(params![limit as i64], |row| Ok(row_to_meta(row)))?
            .collect::<Result<Vec<_>, _>>()?;
        Ok(rows)
    }

    pub fn retain(&self, alive_paths: Vec<String>) -> Result<(), IndexError> {
        let conn = self.conn.lock();
        conn.execute_batch("CREATE TEMP TABLE IF NOT EXISTS keep(path TEXT PRIMARY KEY);")?;
        conn.execute("DELETE FROM keep", [])?;
        let mut ins = conn.prepare("INSERT OR IGNORE INTO keep(path) VALUES (?1)")?;
        for p in &alive_paths { ins.execute(params![p])?; }
        drop(ins);
        conn.execute("DELETE FROM notes WHERE path NOT IN (SELECT path FROM keep)", [])?;
        Ok(())
    }
}

fn row_to_meta(row: &rusqlite::Row) -> NoteMeta {
    let tags_joined: String = row.get(6).unwrap_or_default();
    let tags: Vec<String> = if tags_joined.is_empty() { Vec::new() }
        else { tags_joined.split(' ').filter(|s| !s.is_empty()).map(str::to_string).collect() };
    NoteMeta {
        path: row.get(0).unwrap_or_default(),
        parent: row.get(1).unwrap_or_default(),
        title: row.get(2).unwrap_or_default(),
        preview: row.get(3).unwrap_or_default(),
        modified_millis: row.get(4).unwrap_or(0),
        created_millis: row.get(5).unwrap_or(0),
        tags,
        label: row.get(7).ok(),
    }
}

fn escape_fts(q: &str) -> String {
    let mut out = String::with_capacity(q.len() + 8);
    for (i, term) in q.split_whitespace().enumerate() {
        if i > 0 { out.push(' '); }
        let sanitized: String = term.chars().filter(|c| c.is_alphanumeric() || *c == '_' || *c == '-').collect();
        if sanitized.is_empty() { continue; }
        out.push('"'); out.push_str(&sanitized); out.push('"'); out.push('*');
    }
    if out.is_empty() { "\"\"".to_string() } else { out }
}
