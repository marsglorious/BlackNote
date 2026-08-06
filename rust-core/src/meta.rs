#[derive(Clone, Debug)]
pub struct NoteMeta {
    pub path: String,
    pub parent: String,
    pub title: String,
    pub preview: String,
    pub modified_millis: i64,
    pub created_millis: i64,
    pub tags: Vec<String>,
    pub label: Option<String>,
}

pub fn extract_meta(
    path: String,
    parent: String,
    file_name: String,
    text: String,
    modified_millis: i64,
) -> NoteMeta {
    let mut title = String::new();
    let mut label: Option<String> = None;
    let mut tags: Vec<String> = Vec::new();
    let mut body_start = 0usize;
    let mut created_millis: i64 = 0;
    let mut modified_override: Option<i64> = None;

    let trimmed = text.trim_start();
    let leading = text.len() - trimmed.len();
    if trimmed.starts_with("---") {
        if let Some(end) = trimmed[3..].find("---") {
            let yaml = &trimmed[3..3 + end];
            for line in yaml.lines() {
                let line = line.trim();
                if let Some(v) = strip_key(line, "title") {
                    title = v.trim_matches('"').to_string();
                } else if let Some(v) = strip_key(line, "tags") {
                    parse_tag_array(v, &mut tags);
                } else if let Some(v) = strip_key(line, "label") {
                    label = Some(v.trim_matches('"').to_string());
                } else if let Some(v) = strip_key(line, "created") {
                    if let Some(ms) = parse_iso_date(v) { created_millis = ms; }
                } else if let Some(v) = strip_key(line, "modified") {
                    if let Some(ms) = parse_iso_date(v) { modified_override = Some(ms); }
                } else if let Some(v) = strip_key(line, "source") {
                    // surface 'source' as the chip label when no explicit label was set
                    if label.is_none() {
                        let val = v.trim_matches('"').to_string();
                        if !val.is_empty() { label = Some(val); }
                    }
                }
            }
            body_start = leading + 3 + end + 3;
        }
    }

    let body_full = text.get(body_start..).unwrap_or(&text);
    let body = body_full.trim_start();

    if title.is_empty() {
        for line in body.lines() {
            if line.trim_start().starts_with('#') {
                // walk past # chars, only take as title if followed by space (heading) or alphanum (was a hashtag)
                let t = line.trim_start().trim_start_matches('#').trim();
                if !t.is_empty() { title = t.to_string(); break; }
            } else if !line.trim().is_empty() {
                let t = line.trim();
                if !t.is_empty() { title = t.chars().take(80).collect(); break; }
            }
        }
    }
    if title.is_empty() {
        title = file_name.strip_suffix(".md").unwrap_or(&file_name).to_string();
        if title.is_empty() { title = "Untitled".to_string(); }
    }

    // Hashtags from body (Obsidian-style)
    extract_hashtags(body, &mut tags);
    tags.sort();
    tags.dedup();

    let preview: String = body
        .lines()
        .filter(|l| !l.trim().is_empty() && !l.trim_start().starts_with('#'))
        .take(3)
        .collect::<Vec<_>>()
        .join(" ");
    let preview = preview.chars().take(180).collect::<String>();

    NoteMeta {
        path, parent, title, preview,
        modified_millis: modified_override.unwrap_or(modified_millis),
        created_millis, tags, label,
    }
}

fn strip_key<'a>(line: &'a str, key: &str) -> Option<&'a str> {
    let prefix = format!("{}:", key);
    line.strip_prefix(prefix.as_str()).map(str::trim)
}

fn parse_tag_array(v: &str, out: &mut Vec<String>) {
    let inner = v.trim().trim_matches('[').trim_matches(']');
    for raw in inner.split(',') {
        let t = raw.trim().trim_matches('"').trim_matches('\'').to_string();
        if !t.is_empty() { out.push(t); }
    }
}

fn parse_iso_date(s: &str) -> Option<i64> {
    let s = s.trim().trim_matches('"').trim_matches('\'');
    let parts: Vec<&str> = s.split('-').collect();
    if parts.len() != 3 { return None; }
    let year: i64 = parts[0].parse().ok()?;
    let month: i64 = parts[1].parse().ok()?;
    let day: i64 = parts[2].parse().ok()?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) || year < 1970 || year > 2999 {
        return None;
    }
    let mut days: i64 = 0;
    for y in 1970..year { days += if is_leap(y) { 366 } else { 365 }; }
    let dim: [i64; 12] = [31, if is_leap(year) {29} else {28}, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    for m in 0..(month - 1) as usize { days += dim[m]; }
    days += day - 1;
    Some(days * 86_400_000)
}

fn is_leap(y: i64) -> bool { (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0) }

fn extract_hashtags(body: &str, out: &mut Vec<String>) {
    for line in body.lines() {
        let stripped = line.trim_start();
        // Heading line: 1–6 '#' followed by space — skip
        let mut h_idx = 0;
        let bytes = stripped.as_bytes();
        while h_idx < 6 && h_idx < bytes.len() && bytes[h_idx] == b'#' { h_idx += 1; }
        if h_idx > 0 && h_idx < bytes.len() && bytes[h_idx] == b' ' { continue; }

        let lb = line.as_bytes();
        let mut i = 0;
        while i < lb.len() {
            if lb[i] == b'#' && (i == 0 || !is_word_byte(lb[i - 1])) {
                let start = i + 1;
                let mut end = start;
                while end < lb.len() && is_word_byte(lb[end]) { end += 1; }
                if end > start {
                    let tag = &line[start..end];
                    if !tag.bytes().all(|c| c.is_ascii_digit()) {
                        out.push(tag.to_string());
                    }
                }
                i = end;
            } else {
                i += 1;
            }
        }
    }
}

fn is_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_' || b == b'-' || b == b'/'
}
