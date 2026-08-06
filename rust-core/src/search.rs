use crate::meta::NoteMeta;

pub fn search_notes(notes: Vec<NoteMeta>, query: String, limit: u32) -> Vec<NoteMeta> {
    let q = query.trim().to_lowercase();
    if q.is_empty() {
        let mut out = notes;
        out.sort_by(|a, b| b.modified_millis.cmp(&a.modified_millis));
        out.truncate(limit as usize);
        return out;
    }

    let mut scored: Vec<(i64, NoteMeta)> = notes
        .into_iter()
        .filter_map(|n| {
            let title_l = n.title.to_lowercase();
            let preview_l = n.preview.to_lowercase();
            let label_l = n.label.clone().unwrap_or_default().to_lowercase();
            let mut score: i64 = 0;
            for term in q.split_whitespace() {
                if title_l.contains(term) { score += 100; }
                if label_l.contains(term) { score += 60; }
                if preview_l.contains(term) { score += 20; }
            }
            if score == 0 { None } else { Some((score, n)) }
        })
        .collect();

    scored.sort_by(|a, b| b.0.cmp(&a.0).then(b.1.modified_millis.cmp(&a.1.modified_millis)));
    scored.into_iter().take(limit as usize).map(|(_, n)| n).collect()
}
