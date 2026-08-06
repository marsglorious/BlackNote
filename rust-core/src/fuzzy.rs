use crate::meta::NoteMeta;

#[derive(Clone, Debug)]
pub struct FuzzyResult {
    pub note: NoteMeta,
    pub title_matches: Vec<u32>,
    pub preview_matches: Vec<u32>,
}

/// Greedy subsequence matcher in the spirit of fzy/fzf.
/// Returns `Some((score, char_indices))` when every char in `needle` appears in
/// `haystack` in order (case-insensitive), or `None` otherwise.
///
/// Scoring rewards:
///   * consecutive runs (a "soc" inside "socialism" beats one inside "scrolling")
///   * prefix matches (first char hit at index 0)
///   * word boundaries — match after whitespace or punctuation
///   * camel/upper boundaries — match on an uppercase char that wasn't preceded by uppercase
pub fn fuzzy_match(haystack: &str, needle: &str) -> Option<(i32, Vec<u32>)> {
    if needle.is_empty() {
        return Some((0, Vec::new()));
    }
    let h_chars: Vec<char> = haystack.chars().collect();
    let n_lower: Vec<char> = needle.chars().flat_map(char::to_lowercase).collect();
    let mut positions: Vec<u32> = Vec::with_capacity(n_lower.len());
    let mut score: i32 = 0;
    let mut last_match: Option<usize> = None;
    let mut h_idx = 0usize;

    for &nc in &n_lower {
        let mut found = false;
        while h_idx < h_chars.len() {
            let hc = h_chars[h_idx];
            if hc.to_lowercase().next() == Some(nc) {
                let mut char_score: i32 = 1;
                match last_match {
                    Some(last) if h_idx == last + 1 => char_score += 15,
                    None if h_idx == 0 => char_score += 8,
                    _ => {}
                }
                if h_idx > 0 {
                    let prev = h_chars[h_idx - 1];
                    if !prev.is_alphanumeric() { char_score += 10; }
                    else if prev.is_lowercase() && hc.is_uppercase() { char_score += 7; }
                }
                score += char_score;
                positions.push(h_idx as u32);
                last_match = Some(h_idx);
                h_idx += 1;
                found = true;
                break;
            }
            h_idx += 1;
        }
        if !found { return None; }
    }
    Some((score, positions))
}

pub fn fuzzy_search(notes: Vec<NoteMeta>, query: String, limit: u32) -> Vec<FuzzyResult> {
    let q = query.trim();
    if q.is_empty() {
        let mut sorted = notes;
        sorted.sort_by(|a, b| b.modified_millis.cmp(&a.modified_millis));
        return sorted.into_iter().take(limit as usize)
            .map(|n| FuzzyResult { note: n, title_matches: Vec::new(), preview_matches: Vec::new() })
            .collect();
    }
    let mut results: Vec<(i32, FuzzyResult)> = Vec::new();
    for note in notes {
        let title = fuzzy_match(&note.title, q);
        let preview = fuzzy_match(&note.preview, q);
        let label = note.label.as_deref().and_then(|l| fuzzy_match(l, q));

        let (title_positions, title_score) = title
            .map(|(s, p)| (p, s * 3))
            .unwrap_or((Vec::new(), 0));
        let (preview_positions, preview_score) = preview
            .map(|(s, p)| (p, s))
            .unwrap_or((Vec::new(), 0));
        let label_score = label.map(|(s, _)| s * 2).unwrap_or(0);

        let score = title_score.max(preview_score).max(label_score);
        if score > 0 {
            results.push((score, FuzzyResult {
                note,
                title_matches: title_positions,
                preview_matches: preview_positions,
            }));
        }
    }
    results.sort_by(|a, b| b.0.cmp(&a.0).then(b.1.note.modified_millis.cmp(&a.1.note.modified_millis)));
    results.into_iter().take(limit as usize).map(|(_, r)| r).collect()
}
